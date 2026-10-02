package com.nostr.torinos.emoji

import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayStore
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** リレーで公開されている kind 30030 絵文字セット。 */
data class PublishedEmojiSet(
    val address: EmojiSetAddress,
    val sourceEventId: String,
    val name: String,
    val createdAt: Long,
    val emojis: List<CustomEmoji>,
) {
    val authorPubkey: String get() = address.author
}

data class EmojiSetDiscoveryState(
    val relayUrl: String? = null,
    val publishedSets: List<PublishedEmojiSet> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingReferences: Boolean = false,
    val failed: Boolean = false,
    val failedReferences: Set<EmojiSetAddress> = emptySet(),
)

/** 公開カタログは選択した1リレーから取得。自分の登録同期とは独立する。 */
object EmojiSetDiscovery {
    private const val STALE_AFTER_MS = 5 * 60 * 1_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val transport = EmojiDiscoveryTransport()
    private val _state = MutableStateFlow(EmojiSetDiscoveryState())
    val state: StateFlow<EmojiSetDiscoveryState> = _state.asStateFlow()
    private val cache = linkedMapOf<String, Pair<Long, EmojiSetDiscoveryState>>()
    private val users = mutableMapOf<String, Int>()
    private var generation = 0L
    private var requestId = 0L
    private var listJob: Job? = null

    /** 画面・ピッカーの共通取得。最後の利用者が離れたら購読を閉じる。 */
    fun acquire(relay: String): () -> Unit {
        users[relay] = (users[relay] ?: 0) + 1
        ensureLoaded(relay)
        var released = false
        return release@{
            if (released) return@release
            released = true
            users[relay] = ((users[relay] ?: 1) - 1).coerceAtLeast(0)
            if (users[relay] == 0) {
                users.remove(relay)
                if (_state.value.relayUrl == relay) {
                    listJob?.cancel()
                    ++generation
                    _state.update { it.copy(isLoading = false, isLoadingReferences = false) }
                }
            }
        }
    }

    fun ensureLoaded(relay: String? = RelayStore.selectedEmojiRelayUrl.value) {
        relay ?: return
        if (_state.value.relayUrl != relay) {
            listJob?.cancel()
            ++generation
            _state.value = cache[relay]?.second ?: EmojiSetDiscoveryState(relayUrl = relay)
        }
        if (listJob?.isActive == true) return
        val saved = cache[relay]
        if (saved != null && !saved.second.failed && nowMillis() - saved.first < STALE_AFTER_MS) return
        refresh()
    }

    fun refresh() {
        val relay = _state.value.relayUrl
            ?: RelayStore.selectedEmojiRelayUrl.value ?: return
        if (listJob?.isActive == true && _state.value.relayUrl == relay) return
        val token = ++generation
        _state.update { it.copy(relayUrl = relay, isLoading = true, isLoadingReferences = false, failed = false) }
        listJob = scope.launch {
            val result = transport.fetch(nextSubId(), relay,
                listOf(NostrFilter(kinds = listOf(EmojiSetAddress.KIND_EMOJI_SET), limit = 100)))
            if (token != generation) return@launch
            _state.update { current -> current.copy(
                publishedSets = deduplicatePublishedEmojiSets(
                    current.publishedSets + result.events.mapNotNull { it.toPublishedEmojiSet() }).take(3_000),
                isLoading = false,
                failed = !result.complete,
            ) }
            saveCache(relay, freshList = result.complete)
        }
    }

    /** アドレスの正確な組み合わせで追加取得。別リレーへフォールバックしない。 */
    suspend fun fetchReferences(addresses: List<EmojiSetAddress>) {
        val relay = _state.value.relayUrl ?: return
        val token = generation
        val missing = addresses.distinct().filter { address -> _state.value.publishedSets.none { it.address == address } }
        if (missing.isEmpty()) return
        _state.update { it.copy(isLoadingReferences = true) }
        try {
            missing.chunked(20).forEach { batch ->
                val result = transport.fetch(nextSubId(), relay, batch.map { it.toFilter() })
                if (token != generation || _state.value.relayUrl != relay) return
                val sets = result.events.mapNotNull { it.toPublishedEmojiSet() }.filter { it.address in batch }
                _state.update { current -> current.copy(
                    publishedSets = deduplicatePublishedEmojiSets(current.publishedSets + sets).take(3_000),
                    failedReferences = (current.failedReferences - sets.map { it.address }.toSet()) +
                        (batch.toSet() - sets.map { it.address }.toSet()),
                ) }
            }
        } finally {
            if (token == generation && _state.value.relayUrl == relay) {
                _state.update { it.copy(isLoadingReferences = false) }
                saveCache(relay)
            }
        }
    }

    suspend fun fetch(address: EmojiSetAddress): PublishedEmojiSet? {
        val relay = RelayStore.selectedEmojiRelayUrl.value ?: return null
        ensureLoaded(relay)
        _state.value.publishedSets.firstOrNull { it.address == address }?.let { return it }
        val result = transport.fetch(nextSubId(), relay, listOf(address.toFilter()))
        return result.events.mapNotNull { it.toPublishedEmojiSet() }.firstOrNull { it.address == address }
    }

    private fun saveCache(relay: String, freshList: Boolean = false) {
        val fetchedAt = if (freshList) nowMillis() else cache[relay]?.first ?: 0L
        cache.remove(relay)
        cache[relay] = fetchedAt to _state.value.copy(isLoading = false, isLoadingReferences = false)
        while (cache.size > 2) cache.remove(cache.keys.first())
    }
    private fun nextSubId() = "emoji-catalog-${++requestId}"
    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

/**
 * アドレスごとに最新の1件にまとめる。同名でも別アドレスなら別セットとして保持する。
 */
internal fun deduplicatePublishedEmojiSets(
    sets: Collection<PublishedEmojiSet>,
): List<PublishedEmojiSet> = sets
    .sortedWith(
        compareByDescending<PublishedEmojiSet> { it.createdAt }
            .thenBy { it.sourceEventId },
    )
    .distinctBy { it.address }
    .sortedWith(
        compareByDescending<PublishedEmojiSet> { it.createdAt }
            .thenBy { it.name.lowercase() }
            .thenBy { it.sourceEventId },
    )
