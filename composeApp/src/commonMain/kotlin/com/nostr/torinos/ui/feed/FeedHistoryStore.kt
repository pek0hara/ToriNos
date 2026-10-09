package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent

internal data class StoredFeedEvent(
    val event: NostrEvent,
    val relayUrls: Set<String>,
    val filterKeys: Set<Int>,
)

internal sealed interface FeedStoreCommitResult {
    data object Committed : FeedStoreCommitResult
    data object RejectedStaleScope : FeedStoreCommitResult
    data object Failed : FeedStoreCommitResult
}

/**
 * セッション内の生イベント。表示用キャッシュの追い出しで取得済みページを失わない。
 * 上限に達した後のライブ受信は、[liveTrimSlack] 件まで上限を超えて溜めてからまとめて削る。
 * 1件ごとに削ると、受信のたびに全件の並べ替えと表示の組み直しがメインスレッドで走るため。
 */
internal open class FeedHistoryStore(val softLimit: Int = 5_000, val liveTrimSlack: Int = 200) {
    private var generation = 0
    private val events = linkedMapOf<String, StoredFeedEvent>()
    var limitReached = false
        private set

    val snapshot: List<StoredFeedEvent> get() = events.values.toList()

    /** ライブ受信で上限を余裕分より多く超えたので、[trimLive] で上限まで削る。 */
    val needsLiveTrim: Boolean get() = limitReached && events.size > softLimit + liveTrimSlack

    fun replaceScope(nextGeneration: Int) {
        generation = nextGeneration
        events.clear()
        limitReached = false
    }

    open fun commit(scopeGeneration: Int, page: List<StoredFeedEvent>): FeedStoreCommitResult {
        if (scopeGeneration != generation) return FeedStoreCommitResult.RejectedStaleScope
        // 検証が終わってから全件を反映する。開始済みページはソフト上限で切り捨てない。
        val merged = linkedMapOf<String, StoredFeedEvent>()
        page.forEach { source ->
            val previous = merged[source.event.id] ?: events[source.event.id]
            merged[source.event.id] = source.copy(
                relayUrls = source.relayUrls + previous?.relayUrls.orEmpty(),
                filterKeys = source.filterKeys + previous?.filterKeys.orEmpty(),
            )
        }
        events.putAll(merged)
        if (events.size >= softLimit) limitReached = true
        return FeedStoreCommitResult.Committed
    }

    open fun trimLive(protectedIds: Set<String>): List<StoredFeedEvent> {
        val removed = mutableListOf<StoredFeedEvent>()
        if (!limitReached) return removed
        events.values.sortedBy { it.event.createdAt }.forEach { source ->
            if (events.size <= softLimit) return@forEach
            if (source.event.id !in protectedIds) events.remove(source.event.id)?.let(removed::add)
        }
        return removed
    }

    fun remove(id: String) { events.remove(id) }
}

internal sealed interface FeedPageCommitResult {
    /** [added] は保持へ加えた件数、[visibleAdded] はそのうちミュート・NGワードで隠れない件数。 */
    data class Committed(val visibleAdded: Int, val added: Int = visibleAdded) : FeedPageCommitResult
    data object RejectedStaleScope : FeedPageCommitResult
    data object Failed : FeedPageCommitResult
}
