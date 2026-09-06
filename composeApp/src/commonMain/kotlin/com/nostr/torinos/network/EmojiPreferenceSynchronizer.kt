package com.nostr.torinos.network

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.util.appLog
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** NIP-51 kind:10030 の preferred emojis と emoji set pointers を端末状態と同期する。 */
class EmojiPreferenceSynchronizer internal constructor(
    private val session: AccountSession,
    private val relayStore: AccountRelayStore,
    private val scope: CoroutineScope,
) {
    private val operationMutex = Mutex()
    private var publishJob: Job? = null
    private var retryJob: Job? = null
    private var pendingSnapshot: EmojiPreferenceSnapshot? = null
    private var generation = 0L
    private var latestEvent: NostrEvent? = null
    private var unresolvedSetReferences: Set<String> = emptySet()
    private var started = false

    internal fun start() {
        if (started) return
        started = true
        scope.launch {
            CustomEmojiStore.activateAccount(session.pubkey, ::schedulePublish)
            relayStore.isLoaded.first { it }
            runCatching { refreshFromRelays() }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        appLog("[EmojiPreferenceSynchronizer] initial sync failed: ${error.message}")
                    }
                }
            retryPendingPublish()
            if (latestEvent == null &&
                (CustomEmojiStore.favoriteEmojis.value.isNotEmpty() || CustomEmojiStore.emojiLists.value.isNotEmpty())
            ) {
                schedulePublish()
            }
            retryJob = launch {
                while (true) {
                    delay(RETRY_INTERVAL_MS)
                    runCatching { retryPendingPublish() }
                        .onFailure { error ->
                            if (error !is CancellationException) {
                                appLog("[EmojiPreferenceSynchronizer] retry failed: ${error.message}")
                            }
                        }
                }
            }
        }
    }

    private fun schedulePublish() {
        pendingSnapshot = EmojiPreferenceSnapshot(
            favorites = CustomEmojiStore.favoriteEmojis.value,
            lists = CustomEmojiStore.emojiLists.value,
        )
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(PUBLISH_DEBOUNCE_MS)
            val snapshot = pendingSnapshot ?: return@launch
            runCatching { publishCurrentPreferences(snapshot) }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        appLog("[EmojiPreferenceSynchronizer] publish failed: ${error.message}")
                    }
                }
        }
    }

    internal suspend fun refreshFromRelays() = operationMutex.withLock {
        session.ensureActive()
        val pending = EmojiPreferenceOutbox.get(session.pubkey)?.event
        val remote = fetchLatestEvent()
        val selected = listOfNotNull(remote, pending).maxWithOrNull(EVENT_COMPARATOR)
        if (selected == null) return@withLock
        latestEvent = selected

        val parsed = parseEmojiPreferenceTags(selected.tags)
        val localLists = CustomEmojiStore.emojiLists.value.mapNotNull { list ->
            list.toEmojiSetReference()?.let { it to list }
        }.toMap()
        val loadedLists = fetchReferencedEmojiSets(parsed.setReferences)
        val listsById = loadedLists.mapNotNull { list ->
            list.toEmojiSetReference()?.let { it to list }
        }.toMap()
        unresolvedSetReferences = parsed.setReferences.filterNot { it in listsById }.toSet()
        val resolvedLists = parsed.setReferences.mapNotNull { reference ->
            listsById[reference] ?: localLists[reference]
        }
        CustomEmojiStore.applySyncedPreferences(parsed.emojis, resolvedLists)
    }

    private suspend fun publishCurrentPreferences(snapshot: EmojiPreferenceSnapshot) = operationMutex.withLock {
        session.ensureActive()
        val favorites = snapshot.favorites
        val lists = snapshot.lists
        val references = lists.mapNotNull(CustomEmojiList::toEmojiSetReference).toSet() + unresolvedSetReferences
        val inlineEmojis = favorites + lists
            .filter { it.toEmojiSetReference() == null }
            .flatMap { it.emojis }
        val tags = buildEmojiPreferenceTags(
            previousTags = latestEvent?.tags.orEmpty(),
            emojis = inlineEmojis,
            setReferences = references,
        )
        val event = session.signer.sign(
            content = latestEvent?.content.orEmpty(),
            kind = KIND_EMOJI_PREFERENCES,
            tags = tags,
            createdAt = maxOf(
                Clock.System.now().epochSeconds,
                (latestEvent?.createdAt ?: -1L) + 1L,
            ),
        )
        val targets = relayStore.enabledRelayUrlsSnapshot()
        check(targets.isNotEmpty()) { "有効なリレーがありません" }
        EmojiPreferenceOutbox.put(event, targets)
        CustomEmojiStore.applySyncedPreferences(snapshot.favorites, snapshot.lists)
        val result = NostrRepository.publishToRelaysWithResult(event, targets)
        EmojiPreferenceOutbox.markSucceeded(event, result.succeededRelays)
        check(result.succeededRelays.isNotEmpty()) { "絵文字設定をリレーへ送信できませんでした" }
        session.ensureActive()
        latestEvent = event
        if (pendingSnapshot == snapshot) {
            CustomEmojiStore.applySyncedPreferences(snapshot.favorites, snapshot.lists)
            pendingSnapshot = null
        }
    }

    private suspend fun retryPendingPublish() {
        val pending = EmojiPreferenceOutbox.get(session.pubkey) ?: return
        val targets = pending.pendingRelayUrls
        if (targets.isEmpty()) return
        val result = NostrRepository.publishToRelaysWithResult(pending.event, targets)
        EmojiPreferenceOutbox.markSucceeded(pending.event, result.succeededRelays)
        if (result.succeededRelays.isNotEmpty()) {
            session.ensureActive()
            if (latestEvent == null || pending.event.isNewerThan(latestEvent!!)) latestEvent = pending.event
        }
    }

    private suspend fun fetchLatestEvent(): NostrEvent? {
        val subId = "emoji-preferences-${session.sessionId.takeLast(12)}-${generation++}"
        val mutex = Mutex()
        var latest: NostrEvent? = null
        val collector = scope.launch {
            NostrRepository.events(subId).collect { event ->
                if (event.kind != KIND_EMOJI_PREFERENCES || event.pubkey != session.pubkey) return@collect
                mutex.withLock {
                    if (latest == null || event.isNewerThan(latest!!)) latest = event
                }
            }
        }
        return try {
            NostrRepository.subscribe(
                subId,
                NostrFilter(kinds = listOf(KIND_EMOJI_PREFERENCES), authors = listOf(session.pubkey), limit = 1),
            )
            withTimeoutOrNull(FETCH_TIMEOUT_MS) { NostrRepository.eose(subId).first() }
            mutex.withLock { latest }
        } finally {
            collector.cancel()
            NostrRepository.close(subId)
        }
    }

    private suspend fun fetchReferencedEmojiSets(references: List<String>): List<CustomEmojiList> {
        val addresses = references.mapNotNull(::parseEmojiSetReference)
        if (addresses.isEmpty()) return emptyList()
        val subId = "emoji-set-refs-${session.sessionId.takeLast(12)}-${generation++}"
        val mutex = Mutex()
        val latest = mutableMapOf<String, NostrEvent>()
        val expected = addresses.associateBy { it.reference }
        val collector = scope.launch {
            NostrRepository.events(subId).collect { event ->
                val identifier = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: return@collect
                val reference = "$KIND_EMOJI_SET:${event.pubkey}:$identifier"
                if (event.kind != KIND_EMOJI_SET || reference !in expected) return@collect
                mutex.withLock {
                    val current = latest[reference]
                    if (current == null || event.isNewerThan(current)) latest[reference] = event
                }
            }
        }
        return try {
            NostrRepository.subscribe(
                subId,
                NostrFilter(
                    kinds = listOf(KIND_EMOJI_SET),
                    authors = addresses.map { it.author }.distinct(),
                    dTags = addresses.map { it.identifier }.distinct(),
                    limit = addresses.size.coerceAtMost(500),
                ),
            )
            withTimeoutOrNull(FETCH_TIMEOUT_MS) { NostrRepository.eose(subId).first() }
            mutex.withLock { latest.mapNotNull { (reference, event) -> event.toCustomEmojiList(reference) } }
        } finally {
            collector.cancel()
            NostrRepository.close(subId)
        }
    }

    internal fun close() {
        publishJob?.cancel()
        retryJob?.cancel()
        CustomEmojiStore.deactivateAccount(session.pubkey)
    }

    private fun NostrEvent.toCustomEmojiList(reference: String): CustomEmojiList? {
        val identifier = tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: return null
        val title = tags.firstOrNull { it.firstOrNull() == "title" }?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() } ?: identifier
        val emojis = tags.mapNotNull { tag ->
            if (tag.firstOrNull() != "emoji") return@mapNotNull null
            val shortcode = tag.getOrNull(1)?.trim()?.trim(':')?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = tag.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CustomEmoji(shortcode, url)
        }.distinctBy { it.shortcode }
        return emojis.takeIf { it.isNotEmpty() }?.let {
            CustomEmojiList(reference.removePrefix("$KIND_EMOJI_SET:"), title, it, pubkey)
        }
    }
}

private data class EmojiPreferenceSnapshot(
    val favorites: List<CustomEmoji>,
    val lists: List<CustomEmojiList>,
)

internal data class ParsedEmojiPreferences(
    val emojis: List<CustomEmoji>,
    val setReferences: List<String>,
)

internal fun parseEmojiPreferenceTags(tags: List<List<String>>): ParsedEmojiPreferences =
    ParsedEmojiPreferences(
        emojis = tags.mapNotNull { tag ->
            if (tag.firstOrNull() != "emoji") return@mapNotNull null
            val shortcode = tag.getOrNull(1)?.trim()?.trim(':')?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = tag.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CustomEmoji(shortcode, url)
        }.distinct(),
        setReferences = tags.mapNotNull { tag ->
            tag.getOrNull(1)?.takeIf { tag.firstOrNull() == "a" && it.asEmojiSetReference() != null }
        }.distinct(),
    )

internal fun buildEmojiPreferenceTags(
    previousTags: List<List<String>>,
    emojis: List<CustomEmoji>,
    setReferences: Collection<String>,
): List<List<String>> = previousTags.filterNot { it.firstOrNull() == "emoji" || it.firstOrNull() == "a" } +
    emojis.distinct().map { listOf("emoji", it.shortcode.trim().trim(':'), it.imageUrl.trim()) }
        .filter { it[1].isNotBlank() && it[2].isNotBlank() } +
    setReferences.mapNotNull { it.asEmojiSetReference() }.distinct().map { listOf("a", it) }

private data class EmojiSetAddress(val reference: String, val author: String, val identifier: String)

private fun parseEmojiSetReference(value: String): EmojiSetAddress? {
    val parts = value.split(':', limit = 3)
    if (parts.size != 3 || parts[0] != KIND_EMOJI_SET.toString() || parts[1].length != 64 || parts[2].isBlank()) return null
    return EmojiSetAddress(value, parts[1], parts[2])
}

private fun String.asEmojiSetReference(): String? = parseEmojiSetReference(this)?.reference

internal fun CustomEmojiList.toEmojiSetReference(): String? {
    id.asEmojiSetReference()?.let { return it }
    val storedParts = id.split(':', limit = 2)
    if (storedParts.size == 2 && storedParts[0].length == 64 && storedParts[1].isNotBlank()) {
        return "$KIND_EMOJI_SET:$id"
    }
    val author = authorPubkey.trim()
    if (author.length == 64 && id.isNotBlank() && ':' !in id) {
        return "$KIND_EMOJI_SET:$author:$id"
    }
    return null
}

private fun NostrEvent.isNewerThan(other: NostrEvent): Boolean =
    createdAt > other.createdAt || (createdAt == other.createdAt && id < other.id)

@Serializable
private data class PendingEmojiPreferencePublish(
    val event: NostrEvent,
    val pendingRelayUrls: Set<String>,
)

private object EmojiPreferenceOutbox {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(pubkey: String): PendingEmojiPreferencePublish? = mutex.withLock {
        runCatching {
            LocalSettingsStorage.getString(key(pubkey))
                ?.let { json.decodeFromString<PendingEmojiPreferencePublish>(it) }
                ?.takeIf { it.event.kind == KIND_EMOJI_PREFERENCES && it.event.pubkey == pubkey }
        }.getOrNull()
    }

    suspend fun put(event: NostrEvent, relayUrls: Collection<String>) = mutex.withLock {
        val pending = PendingEmojiPreferencePublish(
            event = event,
            pendingRelayUrls = relayUrls.mapNotNull(::normalizeRelayUrl).toSet(),
        )
        LocalSettingsStorage.putString(key(event.pubkey), json.encodeToString(pending))
    }

    suspend fun markSucceeded(event: NostrEvent, relayUrls: Collection<String>) = mutex.withLock {
        val saved = LocalSettingsStorage.getString(key(event.pubkey))
            ?.let { runCatching { json.decodeFromString<PendingEmojiPreferencePublish>(it) }.getOrNull() }
        if (saved?.event?.id != event.id) return@withLock
        val remaining = saved.pendingRelayUrls - relayUrls.mapNotNull(::normalizeRelayUrl).toSet()
        LocalSettingsStorage.putString(
            key(event.pubkey),
            remaining.takeIf { it.isNotEmpty() }
                ?.let { json.encodeToString(saved.copy(pendingRelayUrls = it)) },
        )
    }

    private fun key(pubkey: String) = "emoji_preference_outbox_$pubkey"
}

private const val KIND_EMOJI_PREFERENCES = 10030
private const val KIND_EMOJI_SET = 30030
private const val FETCH_TIMEOUT_MS = 8_000L
private const val PUBLISH_DEBOUNCE_MS = 350L
private const val RETRY_INTERVAL_MS = 30_000L
private val EVENT_COMPARATOR = compareBy<NostrEvent> { it.createdAt }.thenByDescending { it.id }
