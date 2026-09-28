package com.nostr.torinos.emoji

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.normalizeRelayUrl
import com.nostr.torinos.util.appLog
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** kind 10030 / 30030 の取得と送信。テストでは差し替える。 */
internal interface EmojiPreferenceTransport {
    suspend fun awaitRelaysLoaded()
    suspend fun fetchLatestPreferences(pubkey: String): NostrEvent?
    suspend fun fetchSets(addresses: List<EmojiSetAddress>): Map<EmojiSetAddress, RegisteredEmojiSet>
    fun writableRelays(): List<String>

    /** 受理したリレーの URL を返す。 */
    suspend fun publish(event: NostrEvent, relayUrls: Collection<String>): Set<String>
}

/**
 * NIP-51 kind 10030（使う絵文字）を端末の [CustomEmojiRepository] と同期する。
 *
 * - 起動時と、アプリの前面復帰時（前回取得から [REFRESH_MIN_INTERVAL_MS] 以上経過）に取得する（決定事項 D3）。
 * - 端末の変更は [PUBLISH_DEBOUNCE_MS] 待ってから送信する。送信処理は端末の状態を書き換えない。
 * - 取得した内容は、未送信の変更がなければ取り込み、あれば端末側を送り直す（3.4 の競合規則）。
 * - 送信できなかったリレーは送信箱に残し、[RETRY_INTERVAL_MS] ごとに再送する。
 */
class EmojiPreferenceSync internal constructor(
    private val pubkey: String,
    private val signer: AccountSigner,
    private val repository: CustomEmojiRepository,
    private val scope: CoroutineScope,
    private val transport: EmojiPreferenceTransport,
    private val ensureActive: () -> Unit,
    private val outbox: EmojiPreferenceOutbox = EmojiPreferenceOutbox(),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val operationMutex = Mutex()
    private var publishJob: Job? = null
    private var latestEvent: NostrEvent? = null
    private var lastRefreshAtMillis: Long? = null
    private var started = false

    internal fun start() {
        if (started) return
        started = true
        scope.launch {
            repository.awaitLoaded()
            transport.awaitRelaysLoaded()
            logFailure("initial sync") { refresh() }
            logFailure("retry") { retryPendingPublish() }
            val current = repository.preferences.value
            if (latestEvent == null && (current.favorites.isNotEmpty() || current.sets.isNotEmpty())) {
                // リレーにまだ無ければ、端末の内容を初回送信する。
                schedulePublish(force = true)
            }
            launch { repository.localChanges.drop(1).collect { schedulePublish() } }
            while (true) {
                delay(RETRY_INTERVAL_MS)
                logFailure("retry") { retryPendingPublish() }
            }
        }
    }

    /** アプリが前面に戻ったとき。初回取得が済み、前回から一定時間たっていれば取り直す。 */
    internal fun onAppForeground() {
        val last = lastRefreshAtMillis ?: return
        if (now() - last < REFRESH_MIN_INTERVAL_MS) return
        lastRefreshAtMillis = now()
        scope.launch { logFailure("foreground sync") { refresh() } }
    }

    internal suspend fun refresh() = operationMutex.withLock {
        ensureActive()
        lastRefreshAtMillis = now()
        val pending = outbox.get(pubkey)?.event
        val selected = listOfNotNull(transport.fetchLatestPreferences(pubkey), pending, latestEvent).newestOrNull()
            ?: return@withLock
        latestEvent = selected

        val parsed = parseEmojiPreferenceTags(selected.tags)
        val loaded = transport.fetchSets(parsed.setReferences)
        ensureActive()
        val localSets = repository.preferences.value.sets.associateBy { it.address }
        val resolved = parsed.setReferences.mapNotNull { loaded[it] ?: localSets[it] }
        val unresolved = parsed.setReferences.filterNot { it in loaded }.toSet()
        val applied = repository.applyRemote(parsed.emojis, resolved, unresolved)
        if (applied.hasUnsyncedChanges) schedulePublish()
    }

    private fun schedulePublish(force: Boolean = false) {
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(PUBLISH_DEBOUNCE_MS)
            logFailure("publish") { publish(force) }
        }
    }

    private suspend fun publish(force: Boolean) = operationMutex.withLock {
        ensureActive()
        val snapshot = repository.preferences.value
        if (!snapshot.hasUnsyncedChanges && !force) return@withLock
        val previous = latestEvent
        val event = signer.sign(
            content = previous?.content.orEmpty(),
            kind = KIND_EMOJI_PREFERENCES,
            tags = buildEmojiPreferenceTags(
                previousTags = previous?.tags.orEmpty(),
                emojis = snapshot.favorites,
                setReferences = snapshot.sets.map { it.address } + snapshot.unresolvedSetAddresses,
            ),
            createdAt = maxOf(now() / 1_000L, (previous?.createdAt ?: -1L) + 1L),
        )
        val targets = transport.writableRelays()
        check(targets.isNotEmpty()) { "書き込み可能なリレーがありません" }
        outbox.put(event, targets)
        val succeeded = transport.publish(event, targets)
        outbox.markSucceeded(event, succeeded)
        check(succeeded.isNotEmpty()) { "絵文字設定をリレーへ送信できませんでした" }
        ensureActive()
        latestEvent = event
        repository.markSynced(snapshot.revision)
    }

    private suspend fun retryPendingPublish() {
        val pending = outbox.get(pubkey) ?: return
        if (pending.pendingRelayUrls.isEmpty()) return
        val succeeded = transport.publish(pending.event, pending.pendingRelayUrls)
        outbox.markSucceeded(pending.event, succeeded)
        if (succeeded.isNotEmpty()) {
            ensureActive()
            operationMutex.withLock {
                val latest = latestEvent
                if (latest == null || pending.event.isNewerThan(latest)) latestEvent = pending.event
            }
        }
    }

    internal fun close() {
        publishJob?.cancel()
    }

    private suspend fun logFailure(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            appLog("[EmojiPreferenceSync] $label failed: ${error.message}")
        }
    }

    internal companion object {
        const val KIND_EMOJI_PREFERENCES = 10030
        const val FETCH_TIMEOUT_MS = 8_000L
        const val PUBLISH_DEBOUNCE_MS = 350L
        const val RETRY_INTERVAL_MS = 30_000L
        const val REFRESH_MIN_INTERVAL_MS = 60_000L
    }
}

internal data class ParsedEmojiPreferences(
    val emojis: List<CustomEmoji>,
    val setReferences: List<EmojiSetAddress>,
)

internal fun parseEmojiPreferenceTags(tags: List<List<String>>): ParsedEmojiPreferences =
    ParsedEmojiPreferences(
        emojis = tags.emojiTags().distinctBy { it.identity },
        setReferences = tags.mapNotNull { tag ->
            tag.getOrNull(1)?.takeIf { tag.firstOrNull() == "a" }?.let(EmojiSetAddress::parse)
        }.distinct(),
    )

/** 既存の kind 10030 のうち `emoji` と `a` だけを置き換え、他のタグは残す。 */
internal fun buildEmojiPreferenceTags(
    previousTags: List<List<String>>,
    emojis: List<CustomEmoji>,
    setReferences: Collection<EmojiSetAddress>,
): List<List<String>> = previousTags.filterNot { it.firstOrNull() == EMOJI_TAG || it.firstOrNull() == "a" } +
    emojis.map { it.toEmojiTag() }
        .filter { it[1].isNotBlank() && it[2].isNotBlank() }
        .distinct() +
    setReferences.distinct().map { listOf("a", it.value) }

/** kind 30030 のイベントを登録用のセットにする。絵文字が無ければ null。 */
internal fun NostrEvent.toRegisteredEmojiSet(): RegisteredEmojiSet? {
    if (kind != EmojiSetAddress.KIND_EMOJI_SET) return null
    val identifier = tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: return null
    val address = EmojiSetAddress.of(pubkey, identifier) ?: return null
    val title = tags.firstOrNull { it.firstOrNull() == "title" }?.getOrNull(1)?.trim()
        ?.takeIf { it.isNotBlank() } ?: identifier
    val emojis = tags.emojiTags().distinctBy { it.identity }
    if (emojis.isEmpty()) return null
    return RegisteredEmojiSet(address, title, emojis)
}

/** NostrRepository を使う実装。 */
internal class NostrEmojiPreferenceTransport(
    private val sessionId: String,
    private val relaysLoaded: suspend () -> Unit,
    private val writableRelayUrls: () -> List<String>,
) : EmojiPreferenceTransport {
    private var generation = 0L

    override suspend fun awaitRelaysLoaded() = relaysLoaded()

    override suspend fun fetchLatestPreferences(pubkey: String): NostrEvent? =
        fetchEventsUntilEose(
            subscriptionId = nextSubId("emoji-preferences"),
            filter = NostrFilter(
                kinds = listOf(EmojiPreferenceSync.KIND_EMOJI_PREFERENCES),
                authors = listOf(pubkey),
                limit = 1,
            ),
            timeoutMs = EmojiPreferenceSync.FETCH_TIMEOUT_MS,
        ) { it.kind == EmojiPreferenceSync.KIND_EMOJI_PREFERENCES && it.pubkey == pubkey }
            .newestOrNull()

    override suspend fun fetchSets(addresses: List<EmojiSetAddress>): Map<EmojiSetAddress, RegisteredEmojiSet> {
        if (addresses.isEmpty()) return emptyMap()
        val expected = addresses.toSet()
        val events = fetchEventsUntilEose(
            subscriptionId = nextSubId("emoji-set-refs"),
            filter = NostrFilter(
                kinds = listOf(EmojiSetAddress.KIND_EMOJI_SET),
                authors = addresses.map { it.author }.distinct(),
                dTags = addresses.map { it.identifier }.distinct(),
                limit = addresses.size.coerceAtMost(500),
            ),
            timeoutMs = EmojiPreferenceSync.FETCH_TIMEOUT_MS,
        ) { it.kind == EmojiSetAddress.KIND_EMOJI_SET }
        return events
            .groupBy { event ->
                event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)
                    ?.let { EmojiSetAddress.of(event.pubkey, it) }
            }
            .filterKeys { it != null && it in expected }
            .mapNotNull { (address, versions) -> versions.newestOrNull()?.toRegisteredEmojiSet()?.let { address!! to it } }
            .toMap()
    }

    override fun writableRelays(): List<String> = writableRelayUrls()

    override suspend fun publish(event: NostrEvent, relayUrls: Collection<String>): Set<String> =
        NostrRepository.publishToRelaysWithResult(event, relayUrls).succeededRelays.toSet()

    private fun nextSubId(prefix: String): String = "$prefix-${sessionId.takeLast(12)}-${generation++}"
}

@Serializable
internal data class PendingEmojiPreferencePublish(
    val event: NostrEvent,
    val pendingRelayUrls: Set<String>,
)

/** 送信しきれなかった kind 10030 を端末に残す。 */
internal class EmojiPreferenceOutbox(
    private val store: EmojiKeyValueStore = LocalSettingsKeyValueStore,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(pubkey: String): PendingEmojiPreferencePublish? = mutex.withLock {
        read(pubkey)?.takeIf {
            it.event.kind == EmojiPreferenceSync.KIND_EMOJI_PREFERENCES && it.event.pubkey == pubkey
        }
    }

    suspend fun put(event: NostrEvent, relayUrls: Collection<String>) = mutex.withLock {
        val pending = PendingEmojiPreferencePublish(
            event = event,
            pendingRelayUrls = relayUrls.mapNotNull(::normalizeRelayUrl).toSet(),
        )
        store.put(key(event.pubkey), json.encodeToString(PendingEmojiPreferencePublish.serializer(), pending))
    }

    suspend fun markSucceeded(event: NostrEvent, relayUrls: Collection<String>) = mutex.withLock {
        val saved = read(event.pubkey)
        if (saved?.event?.id != event.id) return@withLock
        val remaining = saved.pendingRelayUrls - relayUrls.mapNotNull(::normalizeRelayUrl).toSet()
        store.put(
            key(event.pubkey),
            remaining.takeIf { it.isNotEmpty() }
                ?.let { json.encodeToString(PendingEmojiPreferencePublish.serializer(), saved.copy(pendingRelayUrls = it)) },
        )
    }

    private suspend fun read(pubkey: String): PendingEmojiPreferencePublish? = runCatching {
        store.get(key(pubkey))?.let { json.decodeFromString(PendingEmojiPreferencePublish.serializer(), it) }
    }.getOrNull()

    private fun key(pubkey: String) = "emoji_preference_outbox_$pubkey"
}
