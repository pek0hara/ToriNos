package com.nostr.torinos.network

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.Nip44
import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.util.appLog
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
)

data class PrivateMuteListSyncState(
    val isRefreshing: Boolean = false,
    val isPublishing: Boolean = false,
    val lastSyncedAt: Long? = null,
    val error: String? = null,
)

object PrivateMuteListStore {
    private const val KIND_MUTE_LIST = 10000
    private const val CACHE_KEY = "private_mute_list_cache_v1"
    private const val LEGACY_MUTED_PUBKEYS_KEY = "muted_pubkeys"
    private const val LEGACY_NG_WORDS_KEY = "ng_words"
    private const val MAX_WORD_LENGTH = 128
    private const val MAX_WORD_COUNT = 1000
    private const val MAX_NIP44_PLAINTEXT_BYTES = 65535

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _mutedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val mutedPubkeys: StateFlow<Set<String>> = _mutedPubkeys.asStateFlow()

    private val _ngWords = MutableStateFlow<List<String>>(emptyList())
    val ngWords: StateFlow<List<String>> = _ngWords.asStateFlow()

    private val _syncState = MutableStateFlow(PrivateMuteListSyncState())
    val syncState: StateFlow<PrivateMuteListSyncState> = _syncState.asStateFlow()

    init {
        scope.launch {
            loadCache()
            refreshFromRelays(updateStatus = true)
        }
    }

    fun mute(pubkey: String) {
        val normalized = pubkey.trim().lowercase()
        if (!isHex64(normalized) || _mutedPubkeys.value.contains(normalized)) return
        _mutedPubkeys.value = _mutedPubkeys.value + normalized
        persistAndPublish()
    }

    fun unmute(pubkey: String) {
        val normalized = pubkey.trim().lowercase()
        if (!_mutedPubkeys.value.contains(normalized)) return
        _mutedPubkeys.value = _mutedPubkeys.value - normalized
        persistAndPublish()
    }

    fun isMuted(pubkey: String): Boolean =
        runCatching { _mutedPubkeys.value.contains(pubkey.trim().lowercase()) }.getOrDefault(false)

    fun addNgWord(word: String) {
        val normalized = normalizeWord(word) ?: return
        val current = _ngWords.value
        if (current.size >= MAX_WORD_COUNT || current.any { it == normalized }) return
        _ngWords.value = current + normalized
        persistAndPublish()
    }

    fun removeNgWord(word: String) {
        val normalized = normalizeWord(word) ?: word
        val next = _ngWords.value.filter { it != normalized && it != word }
        if (next.size == _ngWords.value.size) return
        _ngWords.value = next
        persistAndPublish()
    }

    fun matchesNgWord(content: String): Boolean =
        runCatching {
            val normalizedContent = content.lowercase()
            _ngWords.value.any { normalizedContent.contains(it) }
        }.getOrDefault(false)

    fun refresh() {
        scope.launch { refreshFromRelays(updateStatus = true) }
    }

    private suspend fun loadCache() {
        val cache = LocalSettingsStorage.getString(CACHE_KEY)
            ?.let { saved ->
                runCatching { json.decodeFromString<PrivateMuteListCache>(saved) }.getOrNull()
            }

        if (cache != null) {
            applyCache(cache)
            return
        }

        val legacyPubkeys = LocalSettingsStorage.getString(LEGACY_MUTED_PUBKEYS_KEY)
            ?.let { saved -> runCatching { json.decodeFromString<List<String>>(saved) }.getOrNull() }
            .orEmpty()
            .map { it.trim().lowercase() }
            .filter(::isHex64)
            .distinct()

        val legacyWords = LocalSettingsStorage.getString(LEGACY_NG_WORDS_KEY)
            ?.let { saved -> runCatching { json.decodeFromString<List<String>>(saved) }.getOrNull() }
            .orEmpty()
            .mapNotNull(::normalizeWord)
            .distinct()
            .take(MAX_WORD_COUNT)

        if (legacyPubkeys.isNotEmpty() || legacyWords.isNotEmpty()) {
            _mutedPubkeys.value = legacyPubkeys.toSet()
            _ngWords.value = legacyWords
            saveCache(null)
        }
    }

    private fun applyCache(cache: PrivateMuteListCache) {
        _mutedPubkeys.value = cache.mutedPubkeys
            .map { it.trim().lowercase() }
            .filter(::isHex64)
            .toSet()
        _ngWords.value = cache.ngWords
            .mapNotNull(::normalizeWord)
            .distinct()
            .take(MAX_WORD_COUNT)
    }

    private fun persistAndPublish() {
        scope.launch {
            saveCache(null)
            publishCurrentList()
        }
    }

    private suspend fun saveCache(source: NostrEvent?) {
        val cache = PrivateMuteListCache(
            mutedPubkeys = _mutedPubkeys.value.toList().sorted(),
            ngWords = _ngWords.value,
            sourceEventId = source?.id,
            sourceCreatedAt = source?.createdAt,
            updatedAt = Clock.System.now().epochSeconds,
        )
        runCatching {
            LocalSettingsStorage.putString(CACHE_KEY, json.encodeToString(cache))
        }.onFailure {
            appLog("[PrivateMuteListStore] cache save failed: ${it::class.simpleName}: ${it.message}")
        }
    }

    private suspend fun refreshFromRelays(updateStatus: Boolean = false) {
        if (updateStatus) {
            _syncState.value = _syncState.value.copy(isRefreshing = true, error = null)
        }
        try {
            val privateKey = KeyStorage.loadPrivateKey() ?: return
            val publicKey = derivePublicKey(privateKey.fromHex()).toHex()
            val event = fetchLatestMuteList(publicKey) ?: run {
                if (updateStatus) {
                    _syncState.value = _syncState.value.copy(
                        isRefreshing = false,
                        lastSyncedAt = Clock.System.now().epochSeconds,
                    )
                }
                return
            }
            val parsed = decodeEventContent(event, privateKey) ?: run {
                if (updateStatus) {
                    _syncState.value = _syncState.value.copy(
                        isRefreshing = false,
                        error = "リレーのミュートリストを読み込めませんでした",
                    )
                }
                return
            }
            _mutedPubkeys.value = parsed.mutedPubkeys.toSet()
            _ngWords.value = parsed.ngWords
            saveCache(event)
            if (updateStatus) {
                _syncState.value = _syncState.value.copy(
                    isRefreshing = false,
                    lastSyncedAt = Clock.System.now().epochSeconds,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            appLog("[PrivateMuteListStore] refresh failed: ${e::class.simpleName}: ${e.message}")
            if (updateStatus) {
                _syncState.value = _syncState.value.copy(
                    isRefreshing = false,
                    error = e.message ?: "リレー同期に失敗しました",
                )
            }
        } finally {
            if (updateStatus && _syncState.value.isRefreshing) {
                _syncState.value = _syncState.value.copy(isRefreshing = false)
            }
        }
    }

    private suspend fun publishCurrentList() {
        val privateKey = KeyStorage.loadPrivateKey() ?: return
        val publicKey = runCatching { derivePublicKey(privateKey.fromHex()).toHex() }.getOrNull() ?: return
        val targets = RelayStore.enabledRelayUrlsSnapshot()
        if (targets.isEmpty()) {
            _syncState.value = _syncState.value.copy(error = "有効なリレーがありません")
            return
        }
        _syncState.value = _syncState.value.copy(isPublishing = true, error = null)
        val tagsJson = encodePrivateTags()
        if (tagsJson.encodeToByteArray().size > MAX_NIP44_PLAINTEXT_BYTES) {
            appLog("[PrivateMuteListStore] private mute list is too large")
            _syncState.value = _syncState.value.copy(isPublishing = false, error = "ミュートリストが大きすぎます")
            return
        }
        val content = runCatching { Nip44.encrypt(tagsJson, privateKey, publicKey) }
            .onFailure { appLog("[PrivateMuteListStore] NIP-44 encrypt failed: ${it::class.simpleName}: ${it.message}") }
            .getOrNull() ?: run {
                _syncState.value = _syncState.value.copy(isPublishing = false, error = "ミュートリストを暗号化できませんでした")
                return
            }
        val event = runCatching {
            signEvent(privateKeyHex = privateKey, content = content, kind = KIND_MUTE_LIST, tags = emptyList())
        }.onFailure {
            appLog("[PrivateMuteListStore] sign failed: ${it::class.simpleName}: ${it.message}")
        }.getOrNull() ?: run {
            _syncState.value = _syncState.value.copy(isPublishing = false, error = "ミュートリストへ署名できませんでした")
            return
        }
        runCatching { NostrRepository.publishToRelays(event, targets) }
            .onSuccess {
                _syncState.value = _syncState.value.copy(
                    isPublishing = false,
                    lastSyncedAt = Clock.System.now().epochSeconds,
                    error = null,
                )
            }
            .onFailure {
                appLog("[PrivateMuteListStore] publish failed: ${it::class.simpleName}: ${it.message}")
                _syncState.value = _syncState.value.copy(
                    isPublishing = false,
                    error = it.message ?: "リレーへ送信できませんでした",
                )
            }
    }

    private fun encodePrivateTags(): String {
        val array = buildJsonArray {
            _mutedPubkeys.value.toList().sorted().forEach { pubkey ->
                add(privateTag("p", pubkey))
            }
            _ngWords.value.forEach { word ->
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
        val subId = "private-mute-${Clock.System.now().epochSeconds}"
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

    private fun decodeEventContent(event: NostrEvent, privateKey: String): ParsedMuteList? {
        if (event.content.isBlank()) return ParsedMuteList()
        return runCatching {
            val plaintext = Nip44.decrypt(event.content, privateKey, event.pubkey)
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
