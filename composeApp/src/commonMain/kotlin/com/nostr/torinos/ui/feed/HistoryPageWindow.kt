package com.nostr.torinos.ui.feed

internal data class HistoryPageWindow(
    val hasMore: Boolean,
    val nextUntil: Long?,
    val revealOldestAt: Long?,
)

/**
 * 複数リレーの応答を、全体として新しい順に1ページずつ公開する。
 *
 * 各リレーはそれぞれ limit 件を返すため、応答全体の最古時刻をカーソルにすると、
 * 投稿の少ないリレーに残る古いイベントまで一度に飛んでしまう。次のカーソルと
 * 表示境界には、応答全体の pageSize 件目の時刻を使う。
 */
internal fun historyPageWindow(
    createdAts: List<Long>,
    pageSize: Int,
    hasMore: Boolean = createdAts.size >= pageSize,
): HistoryPageWindow {
    require(pageSize > 0) { "pageSizeは正の値である必要があります" }
    if (createdAts.isEmpty()) return HistoryPageWindow(false, null, null)

    val newestFirst = createdAts.sortedDescending()
    val revealOldestAt = if (hasMore && newestFirst.size >= pageSize) {
        newestFirst[pageSize - 1]
    } else {
        newestFirst.last()
    }
    return HistoryPageWindow(
        hasMore = hasMore,
        // Nostr の created_at は秒精度なので、境界秒を再取得して ID で重複排除する。
        nextUntil = if (hasMore) revealOldestAt else null,
        revealOldestAt = revealOldestAt,
    )
}
