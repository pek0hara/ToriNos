package com.nostr.torinos.emoji

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.util.appLog
import kotlin.time.Clock
import com.nostr.torinos.network.NostrRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    val publishedSets: List<PublishedEmojiSet> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * 公開絵文字セットの一覧。設定画面と絵文字ピッカーで共有し、購読は同時に1本だけにする。
 * 公開データでアカウントに依存しないため、ログイン前でも使える。
 */
object EmojiSetDiscovery {
    private const val LIST_LIMIT = 100
    private const val FETCH_TIMEOUT_MS = 10_000L
    private const val STALE_AFTER_MS = 5 * 60 * 1_000L
    private const val LIST_OPEN_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(EmojiSetDiscoveryState())
    val state: StateFlow<EmojiSetDiscoveryState> = _state.asStateFlow()

    private var generation = 0L
    private var listJob: Job? = null
    private var loadedAtMillis: Long? = null
    private var refreshToken = 0L

    /** 未取得か、前回の取得から時間がたっていれば取り直す。 */
    fun ensureLoaded() {
        val loadedAt = loadedAtMillis
        if (listJob?.isActive == true) return
        if (loadedAt != null && nowMillis() - loadedAt < STALE_AFTER_MS) return
        refresh()
    }

    /**
     * 一覧を取り直す。最初の EOSE（またはタイムアウト）で読み込み中を解除し、遅いリレーの結果も
     * [LIST_OPEN_MS] の間は受け取り続ける。
     */
    fun refresh() {
        listJob?.cancel()
        val token = ++refreshToken
        loadedAtMillis = nowMillis()
        val latest = mutableMapOf<EmojiSetAddress, PublishedEmojiSet>()
        _state.value = EmojiSetDiscoveryState(isLoading = true)
        val subscriptionId = nextSubId("emoji-set-discovery")
        listJob = scope.launch {
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                NostrRepository.events(subscriptionId).collect { event ->
                    val set = event.toPublishedEmojiSet() ?: return@collect
                    val current = latest[set.address]
                    if (current != null && !set.isPreferredTo(current)) return@collect
                    latest[set.address] = set
                    if (token == refreshToken) {
                        _state.update { it.copy(publishedSets = deduplicatePublishedEmojiSets(latest.values)) }
                    }
                }
            }
            val eose = async(start = CoroutineStart.UNDISPATCHED) { NostrRepository.eose(subscriptionId).first() }
            try {
                NostrRepository.subscribe(
                    subscriptionId,
                    NostrFilter(kinds = listOf(EmojiSetAddress.KIND_EMOJI_SET), limit = LIST_LIMIT),
                )
                withTimeoutOrNull(FETCH_TIMEOUT_MS) { eose.await() }
                if (token == refreshToken) _state.update { it.copy(isLoading = false) }
                delay(LIST_OPEN_MS)
            } catch (error: Throwable) {
                if (error !is CancellationException) appLog("[EmojiSetDiscovery] list failed: ${error.message}")
            } finally {
                collector.cancel()
                eose.cancel()
                if (token == refreshToken) _state.update { it.copy(isLoading = false) }
                NostrRepository.close(subscriptionId)
            }
        }
    }

    /** アドレスを指定して1件取得する。一覧に無い古いセットを開くときに使う。 */
    suspend fun fetch(address: EmojiSetAddress): PublishedEmojiSet? {
        state.value.publishedSets.firstOrNull { it.address == address }?.let { return it }
        return fetchEventsUntilEose(
            subscriptionId = nextSubId("emoji-set-address"),
            filter = NostrFilter(
                kinds = listOf(EmojiSetAddress.KIND_EMOJI_SET),
                authors = listOf(address.author),
                dTags = listOf(address.identifier),
                limit = 1,
            ),
            timeoutMs = FETCH_TIMEOUT_MS,
        ) { event -> event.toPublishedEmojiSet()?.address == address }
            .newestOrNull()
            ?.toPublishedEmojiSet()
    }

    private fun nextSubId(prefix: String): String = "$prefix-${generation++}"

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

internal fun NostrEvent.toPublishedEmojiSet(): PublishedEmojiSet? {
    val set = toRegisteredEmojiSet() ?: return null
    return PublishedEmojiSet(
        address = set.address,
        sourceEventId = id,
        name = set.title,
        createdAt = createdAt,
        emojis = set.emojis,
    )
}

/**
 * リレーが同じアドレスの古い版を返すことや、同じ作者が同名のセットを別の d で作り直すことがある。
 * どちらも最新の1件にまとめ、並びを決定的にする。
 */
internal fun deduplicatePublishedEmojiSets(
    sets: Collection<PublishedEmojiSet>,
): List<PublishedEmojiSet> = sets
    .sortedWith(
        compareByDescending<PublishedEmojiSet> { it.createdAt }
            .thenBy { it.sourceEventId },
    )
    .distinctBy { it.address }
    .distinctBy { set -> "${set.authorPubkey}:${set.name.trim().lowercase()}" }
    .sortedWith(
        compareByDescending<PublishedEmojiSet> { it.createdAt }
            .thenBy { it.name.lowercase() }
            .thenBy { it.sourceEventId },
    )

internal fun PublishedEmojiSet.isPreferredTo(other: PublishedEmojiSet): Boolean =
    createdAt > other.createdAt ||
        (createdAt == other.createdAt && sourceEventId < other.sourceEventId)
