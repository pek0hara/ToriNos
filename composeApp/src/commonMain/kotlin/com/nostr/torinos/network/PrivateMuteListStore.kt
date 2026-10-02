package com.nostr.torinos.network

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.util.appLog
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class PrivateMuteListCache(
    val mutedPubkeys: List<String> = emptyList(),
    val ngWords: List<String> = emptyList(),
    val sourceEventId: String? = null,
    val sourceCreatedAt: Long? = null,
    val updatedAt: Long = 0,
    val revision: Long = 0,
    val hasPendingChanges: Boolean = false,
    val pendingEvent: NostrEvent? = null,
)

data class PrivateMuteListSyncState(
    val isRefreshing: Boolean = false,
    val isPublishing: Boolean = false,
    val lastSyncedAt: Long? = null,
    val error: String? = null,
)

class PrivateMuteListStore internal constructor(
    private val signer: AccountSigner,
    private val sessionId: String,
    private val scope: CoroutineScope,
    private val writableRelayUrls: () -> List<String>,
    private val ensureActive: () -> Unit,
    private val storage: PrivateMuteListStorage = PrivateMuteListStorage(),
    private val fetchRemote: (suspend () -> NostrEvent?)? = null,
    private val publish: suspend (NostrEvent, List<String>) -> RelayPublishResult = { event, targets ->
        NostrRepository.publishToRelaysWithResult(event, targets, awaitAcceptance = true)
    },
) {
    private val KIND_MUTE_LIST = 10000
    private val MAX_WORD_LENGTH = 128
    private val MAX_WORD_COUNT = 1000
    private val MAX_NIP44_PLAINTEXT_BYTES = 65535

    private val json = Json { ignoreUnknownKeys = true }
    private val _mutedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val mutedPubkeys: StateFlow<Set<String>> = _mutedPubkeys.asStateFlow()

    private val _ngWords = MutableStateFlow<List<String>>(emptyList())
    val ngWords: StateFlow<List<String>> = _ngWords.asStateFlow()

    private val _syncState = MutableStateFlow(PrivateMuteListSyncState())
    val syncState: StateFlow<PrivateMuteListSyncState> = _syncState.asStateFlow()

    private val memoryLock = SynchronousLock()
    private val saveMutex = Mutex()
    private val publishMutex = Mutex()
    private val refreshMutex = Mutex()
    private var cache = PrivateMuteListCache()
    private var initialized = false
    private var closed = false
    private var nextFetchNumber = 0L
    private var started = false

    /** Activeを公開する前に読み込む。読み込み前の空状態を編集・公開させない。 */
    internal suspend fun initialize() {
        val loaded = storage.load(signer.pubkey)
        memoryLock.withLock {
            cache = loaded.copy(
                mutedPubkeys = loaded.mutedPubkeys.map { it.trim().lowercase() }.filter(::isHex64).distinct(),
                ngWords = loaded.ngWords.mapNotNull(::normalizeWord).distinct().take(MAX_WORD_COUNT),
            )
            applyCache()
            initialized = true
        }
    }

    internal fun start() {
        if (started) return
        started = true
        scope.launch { refreshFromRelays() }
    }

    fun mute(pubkey: String) {
        val normalized = pubkey.trim().lowercase()
        if (!isHex64(normalized)) return
        edit { current ->
            if (normalized in current.mutedPubkeys) current
            else current.copy(mutedPubkeys = current.mutedPubkeys + normalized)
        }
    }

    fun unmute(pubkey: String) {
        val normalized = pubkey.trim().lowercase()
        edit { it.copy(mutedPubkeys = it.mutedPubkeys - normalized) }
    }

    fun isMuted(pubkey: String): Boolean = pubkey.trim().lowercase() in _mutedPubkeys.value

    fun addNgWord(word: String) {
        val normalized = normalizeWord(word) ?: return
        edit { current ->
            if (current.ngWords.size >= MAX_WORD_COUNT || normalized in current.ngWords) current
            else current.copy(ngWords = current.ngWords + normalized)
        }
    }

    fun removeNgWord(word: String) {
        val normalized = normalizeWord(word) ?: word
        edit { it.copy(ngWords = it.ngWords.filter { value -> value != normalized && value != word }) }
    }

    fun matchesNgWord(content: String): Boolean =
        content.lowercase().let { normalized -> _ngWords.value.any { normalized.contains(it) } }

    private fun edit(transform: (PrivateMuteListCache) -> PrivateMuteListCache) {
        val changed = memoryLock.withLock {
            if (closed || !initialized) return@withLock false
            ensureActive()
            val next = transform(cache)
            if (next == cache) return@withLock false
            cache = next.copy(
                revision = cache.revision + 1,
                hasPendingChanges = true,
                pendingEvent = null,
                updatedAt = Clock.System.now().epochSeconds,
            )
            applyCache()
            true
        }
        if (changed) scope.launch {
            try {
                persistLatest()
                publishPending()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _syncState.value = _syncState.value.copy(error = e.message ?: "ミュートリストを保存できませんでした")
            }
        }
    }

    private fun applyCache() {
        _mutedPubkeys.value = cache.mutedPubkeys.toSet()
        _ngWords.value = cache.ngWords
    }

    /** 保存時点の最新版を1本ずつ書く。古い送信結果の保存で新しい編集を巻き戻さない。 */
    private suspend fun persistLatest() = saveMutex.withLock {
        val snapshot = memoryLock.withLock { cache }
        storage.save(signer.pubkey, snapshot)
    }

    fun refresh() {
        if (started) scope.launch { refreshFromRelays() }
    }

    private suspend fun refreshFromRelays() = refreshMutex.withLock {
        _syncState.value = _syncState.value.copy(isRefreshing = true, error = null)
        try {
            ensureActive()
            val event = fetchRemote?.invoke() ?: if (fetchRemote == null) fetchLatestMuteList(signer.pubkey) else null
            ensureActive()
            if (event != null && event.pubkey == signer.pubkey && event.kind == KIND_MUTE_LIST) {
                val parsed = decodeEventContent(event, signer) ?: error("リレーのミュートリストを読み込めませんでした")
                memoryLock.withLock {
                    if (!closed && (cache.sourceCreatedAt == null || event.createdAt > cache.sourceCreatedAt!! ||
                            (event.createdAt == cache.sourceCreatedAt && event.id < cache.sourceEventId.orEmpty()))) {
                        cache = if (cache.hasPendingChanges) {
                            // 未送信の編集は保持する。再署名時の時刻だけリレーの最新版より後にする。
                            cache.copy(sourceCreatedAt = event.createdAt, sourceEventId = event.id,
                                pendingEvent = cache.pendingEvent?.takeIf { it.createdAt > event.createdAt || it.id == event.id })
                        } else {
                            cache.copy(mutedPubkeys = parsed.mutedPubkeys, ngWords = parsed.ngWords,
                                sourceCreatedAt = event.createdAt, sourceEventId = event.id)
                        }
                        applyCache()
                    }
                }
                persistLatest()
            }
            publishPending()
            _syncState.value = _syncState.value.copy(lastSyncedAt = Clock.System.now().epochSeconds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            appLog("[PrivateMuteListStore] refresh failed: ${e.message}")
            _syncState.value = _syncState.value.copy(error = e.message ?: "リレー同期に失敗しました")
            publishPending()
        } finally {
            _syncState.value = _syncState.value.copy(isRefreshing = false)
        }
    }

    private suspend fun publishPending() = publishMutex.withLock {
        try {
            while (true) {
                ensureActive()
                val snapshot = memoryLock.withLock { cache.takeIf { !closed && it.hasPendingChanges } } ?: break
                val targets = writableRelayUrls()
                check(targets.isNotEmpty()) { "書き込み可能なリレーがありません" }
                _syncState.value = _syncState.value.copy(isPublishing = true, error = null)
                val event = snapshot.pendingEvent ?: run {
                    val plaintext = encodePrivateTags(snapshot.mutedPubkeys.toSet(), snapshot.ngWords)
                    check(plaintext.encodeToByteArray().size <= MAX_NIP44_PLAINTEXT_BYTES) { "ミュートリストが大きすぎます" }
                    signer.sign(
                        content = signer.encryptToSelf(plaintext),
                        kind = KIND_MUTE_LIST,
                        createdAt = maxOf(Clock.System.now().epochSeconds, (snapshot.sourceCreatedAt ?: 0) + 1),
                    )
                }
                check(event.pubkey == signer.pubkey && event.kind == KIND_MUTE_LIST)
                val accepted = memoryLock.withLock {
                    if (closed || cache.revision != snapshot.revision) false
                    else { cache = cache.copy(pendingEvent = event); true }
                }
                if (!accepted) continue
                // 再送可能な署名済みイベントも、送信前に永続化する。
                persistLatest()
                ensureActive()
                val result = publish(event, targets)
                check(result.succeededRelays.isNotEmpty()) { "リレーへ送信できませんでした" }
                ensureActive()
                memoryLock.withLock {
                    if (!closed) {
                        cache = cache.copy(
                            sourceCreatedAt = maxOf(cache.sourceCreatedAt ?: 0, event.createdAt),
                            sourceEventId = if (event.createdAt >= (cache.sourceCreatedAt ?: 0)) event.id else cache.sourceEventId,
                            hasPendingChanges = cache.revision != snapshot.revision ||
                                ((cache.sourceCreatedAt ?: 0) > event.createdAt ||
                                    (cache.sourceCreatedAt == event.createdAt && cache.sourceEventId != event.id)),
                            pendingEvent = null,
                        )
                    }
                }
                persistLatest()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _syncState.value = _syncState.value.copy(error = e.message ?: "リレーへ送信できませんでした")
        } finally {
            _syncState.value = _syncState.value.copy(isPublishing = false)
        }
    }

    internal fun pendingSnapshot(): PrivateMuteListCache? =
        memoryLock.withLock { cache.takeIf { initialized && it.hasPendingChanges } }

    /** 保存失敗によるロールバックでは、旧セッションの未保存編集をメモリから引き継ぐ。 */
    internal fun restorePendingChanges(snapshot: PrivateMuteListCache?) {
        if (snapshot == null) return
        memoryLock.withLock {
            if (snapshot.revision >= cache.revision) {
                cache = snapshot
                applyCache()
            }
        }
    }

    /** 通信は待たず、切り替え直前の最新編集だけを保存する。 */
    internal suspend fun closeAndFlush() {
        val needsSave = memoryLock.withLock { closed = true; initialized }
        if (needsSave) persistLatest()
    }

    private fun encodePrivateTags(mutedPubkeys: Set<String>, ngWords: List<String>): String {
        val array = buildJsonArray {
            mutedPubkeys.toList().sorted().forEach { pubkey ->
                add(privateTag("p", pubkey))
            }
            ngWords.forEach { word ->
                add(privateTag("word", word))
            }
        }
        return json.encodeToString(JsonArray.serializer(), array)
    }

    private fun privateTag(name: String, value: String): JsonArray =
        buildJsonArray {
            add(JsonPrimitive(name))
            add(JsonPrimitive(value))
        }

    private suspend fun fetchLatestMuteList(pubkey: String): NostrEvent? {
        val subId = "private-mute-${sessionId.takeLast(16)}-${nextFetchNumber++}"
        val mutex = Mutex()
        val events = mutableListOf<NostrEvent>()
        val collector = scope.launch {
            NostrRepository.events(subId).collect { event ->
                if (event.kind == KIND_MUTE_LIST && event.pubkey == pubkey) {
                    mutex.withLock {
                        events += event
                    }
                }
            }
        }
        return try {
            NostrRepository.subscribe(
                subId,
                NostrFilter(kinds = listOf(KIND_MUTE_LIST), authors = listOf(pubkey), limit = 1),
            )
            withTimeoutOrNull(8_000L) {
                NostrRepository.eose(subId).first()
            }
            mutex.withLock {
                events.maxByOrNull { it.createdAt }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            appLog("[PrivateMuteListStore] fetch failed: ${e::class.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { NostrRepository.close(subId) }
            collector.cancelAndJoin()
        }
    }

    private fun decodeEventContent(event: NostrEvent, signer: com.nostr.torinos.account.AccountSigner): ParsedMuteList? {
        if (event.content.isBlank()) return ParsedMuteList()
        return runCatching {
            val plaintext = signer.decrypt(event.content, event.pubkey)
            parsePrivateTags(plaintext)
        }.onFailure {
            appLog("[PrivateMuteListStore] decrypt/parse failed: ${it::class.simpleName}: ${it.message}")
        }.getOrNull()
    }

    private fun parsePrivateTags(plaintext: String): ParsedMuteList {
        val tags = json.decodeFromString(JsonArray.serializer(), plaintext)
        val pubkeys = linkedSetOf<String>()
        val words = mutableListOf<String>()
        tags.forEach { element ->
            val tag = runCatching { element.jsonArray }.getOrNull() ?: return@forEach
            val name = tag.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@forEach
            val value = tag.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return@forEach
            when (name) {
                "p" -> value.trim().lowercase().takeIf(::isHex64)?.let(pubkeys::add)
                "word" -> normalizeWord(value)?.takeIf { words.size < MAX_WORD_COUNT && it !in words }?.let(words::add)
            }
        }
        return ParsedMuteList(pubkeys.toList(), words)
    }

    private fun normalizeWord(word: String): String? {
        val normalized = word.trim().lowercase()
        return normalized.takeIf { it.isNotBlank() && it.length <= MAX_WORD_LENGTH }
    }

    private fun isHex64(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private data class ParsedMuteList(
        val mutedPubkeys: List<String> = emptyList(),
        val ngWords: List<String> = emptyList(),
    )
}
