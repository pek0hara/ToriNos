package com.nostr.torinos.journal

import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayTarget
import kotlinx.datetime.LocalDate

/**
 * 1回の有限取得。全リレーが完了したときだけ[dates]の[kinds]を取得済みにする。
 * 結果は[accepts]を満たすイベントだけを使う。
 */
internal data class JournalFetchRequest(
    val dates: List<LocalDate>,
    val kinds: Set<JournalActivityKind>,
    val filters: List<NostrFilter>,
    val target: RelayTarget,
) {
    fun accepts(classified: Set<JournalActivityKind>): Boolean = classified.any { it in kinds }
}

internal data class JournalFetchResult(
    val request: JournalFetchRequest,
    val events: List<NostrEvent>,
    val complete: Boolean,
)

/** 日付・種類・取得済み範囲から、リレーへ送るフィルターを組み立てる純粋処理。 */
internal object JournalFetchPlanner {
    const val DATE_LIMIT = 500
    const val MONTH_RECEIVED_LIKE_LIMIT = 5_000

    /**
     * [date]の[kinds]を取得する。自分が作ったイベントは選択中のリレーから、
     * もらったいいねは届く先が読めないため有効な全リレーから取る。
     */
    fun forDate(
        date: LocalDate,
        kinds: Set<JournalActivityKind>,
        owner: JournalOwner,
        relayUrl: String?,
        clock: JournalClock,
    ): List<JournalFetchRequest> {
        val since = clock.startOfDay(date)
        val until = clock.endOfDay(date)
        return listOfNotNull(
            authored(listOf(date), kinds - JournalActivityKind.ReceivedLike, owner, relayUrl, since, until),
            if (JournalActivityKind.ReceivedLike in kinds) {
                receivedLikes(listOf(date), owner, since, until, DATE_LIMIT)
            } else {
                null
            },
        )
    }

    /** 月のもらったいいねを1回で取る。取得済みの日だけなら取らない。 */
    fun monthReceivedLikes(
        month: LocalDate,
        today: LocalDate,
        kinds: Set<JournalActivityKind>,
        coverage: JournalCoverage,
        owner: JournalOwner,
        clock: JournalClock,
    ): JournalFetchRequest? {
        if (JournalActivityKind.ReceivedLike !in kinds) return null
        val days = month.daysOfMonthUntil(today)
        val missingDates = days.filter { JournalActivityKind.ReceivedLike in coverage.missing(it, kinds) }
        if (missingDates.isEmpty()) return null
        return receivedLikes(
            dates = missingDates,
            owner = owner,
            since = clock.startOfDay(days.first()),
            until = clock.endOfDay(days.last()),
            limit = MONTH_RECEIVED_LIKE_LIMIT,
        )
    }

    /** 月単位でもらったいいねを取るなら、選択日の初回取得からは外す。 */
    fun willFetchMonthReceivedLikes(
        month: LocalDate,
        today: LocalDate,
        kinds: Set<JournalActivityKind>,
        coverage: JournalCoverage,
    ): Boolean = JournalActivityKind.ReceivedLike in kinds &&
        !coverage.hasLoadedMonth(month, setOf(JournalActivityKind.ReceivedLike), today)

    private fun authored(
        dates: List<LocalDate>,
        kinds: Set<JournalActivityKind>,
        owner: JournalOwner,
        relayUrl: String?,
        since: Long,
        until: Long,
    ): JournalFetchRequest? {
        val eventKinds = buildList {
            if (JournalActivityKind.Post in kinds || JournalActivityKind.Reply in kinds) add(1)
            if (JournalActivityKind.Repost in kinds) add(6)
            if (owner.isSelf && JournalActivityKind.Like in kinds) add(7)
        }
        val filters = buildList {
            if (eventKinds.isNotEmpty()) {
                add(
                    NostrFilter(
                        kinds = eventKinds,
                        authors = listOf(owner.pubkey),
                        since = since,
                        until = until,
                        limit = DATE_LIMIT,
                    ),
                )
            }
            if (JournalActivityKind.Reply in kinds) {
                add(
                    NostrFilter(
                        kinds = listOf(COMMENT_EVENT_KIND),
                        authors = listOf(owner.pubkey),
                        rootKindTags = listOf("1"),
                        since = since,
                        until = until,
                        limit = DATE_LIMIT,
                    ),
                )
            }
        }
        if (filters.isEmpty()) return null
        return JournalFetchRequest(
            dates = dates,
            kinds = kinds,
            filters = filters,
            target = relayUrl?.let(RelayTarget::Single) ?: RelayTarget.AllEnabled,
        )
    }

    private fun receivedLikes(
        dates: List<LocalDate>,
        owner: JournalOwner,
        since: Long,
        until: Long,
        limit: Int,
    ): JournalFetchRequest = JournalFetchRequest(
        dates = dates,
        kinds = setOf(JournalActivityKind.ReceivedLike),
        filters = listOf(
            NostrFilter(
                kinds = listOf(7),
                pTags = listOf(owner.pubkey),
                since = since,
                until = until,
                limit = limit,
            ),
        ),
        target = RelayTarget.AllEnabled,
    )
}
