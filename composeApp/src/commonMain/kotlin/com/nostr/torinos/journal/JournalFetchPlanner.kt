package com.nostr.torinos.journal

import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayTarget
import kotlinx.datetime.LocalDate

/**
 * 1回の有限取得。全リレーが完了したときだけ、[members]の[dates]の[kinds]を取得済みにする。
 * 結果は[accepts]を満たすイベントだけを使う。
 */
internal data class JournalFetchRequest(
    val dates: List<LocalDate>,
    val kinds: Set<JournalActivityKind>,
    val members: Set<String>,
    val filters: List<NostrFilter>,
    val target: RelayTarget,
) {
    /** [roles]（メンバーごとの種類）のうち、このリクエストのメンバーと種類に当たるものがあるか。 */
    fun accepts(roles: Map<String, Set<JournalActivityKind>>): Boolean =
        roles.any { (member, kinds) -> member in members && kinds.any { it in this.kinds } }
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
     * [date]で、メンバーごとに足りない種類[missingByMember]を取得する。
     * 足りない種類が同じメンバーは1つのリクエストにまとめる（普段は全員同じなので1本になる）。
     * メンバーが作ったイベントは選択中のリレーから、もらったいいねは届く先が読めないため有効な全リレーから取る。
     */
    fun forDate(
        date: LocalDate,
        missingByMember: Map<String, Set<JournalActivityKind>>,
        relayUrl: String?,
        clock: JournalClock,
    ): List<JournalFetchRequest> {
        val since = clock.startOfDay(date)
        val until = clock.endOfDay(date)
        val authoredRequests = missingByMember
            .mapValues { (_, kinds) -> kinds - JournalActivityKind.ReceivedLike }
            .filterValues { it.isNotEmpty() }
            .entries
            .groupBy({ it.value }, { it.key })
            .mapNotNull { (kinds, members) -> authored(listOf(date), kinds, members.toSet(), relayUrl, since, until) }
        val receivedLikeMembers = missingByMember
            .filterValues { JournalActivityKind.ReceivedLike in it }
            .keys
        val receivedLikeRequest = receivedLikeMembers.takeIf { it.isNotEmpty() }
            ?.let { receivedLikes(listOf(date), it, since, until, DATE_LIMIT) }
        return authoredRequests + listOfNotNull(receivedLikeRequest)
    }

    /** 月のもらったいいねを、まだ取っていないメンバーの分だけ1回で取る。全員取得済みなら取らない。 */
    fun monthReceivedLikes(
        month: LocalDate,
        today: LocalDate,
        kinds: Set<JournalActivityKind>,
        coverage: JournalCoverage,
        members: Collection<String>,
        clock: JournalClock,
    ): JournalFetchRequest? {
        if (JournalActivityKind.ReceivedLike !in kinds) return null
        val days = month.daysOfMonthUntil(today)
        val receivedLike = setOf(JournalActivityKind.ReceivedLike)
        val missingDates = days.filter { date -> coverage.missingByMember(date, receivedLike, members).isNotEmpty() }
        if (missingDates.isEmpty()) return null
        val targets = members.filterTo(mutableSetOf()) { member ->
            missingDates.any { date -> coverage.missing(date, receivedLike, member).isNotEmpty() }
        }
        return receivedLikes(
            dates = missingDates,
            members = targets,
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
        members: Collection<String>,
    ): Boolean = JournalActivityKind.ReceivedLike in kinds &&
        !coverage.hasLoadedMonth(month, setOf(JournalActivityKind.ReceivedLike), today, members)

    private fun authored(
        dates: List<LocalDate>,
        kinds: Set<JournalActivityKind>,
        members: Set<String>,
        relayUrl: String?,
        since: Long,
        until: Long,
    ): JournalFetchRequest? {
        val eventKinds = buildList {
            if (JournalActivityKind.Post in kinds || JournalActivityKind.Reply in kinds) add(1)
            if (JournalActivityKind.Repost in kinds) addAll(listOf(6, 16))
            if (JournalActivityKind.Like in kinds) add(7)
        }
        val filters = buildList {
            if (eventKinds.isNotEmpty()) {
                add(
                    NostrFilter(
                        kinds = eventKinds,
                        authors = members.sorted(),
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
                        authors = members.sorted(),
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
            members = members,
            filters = filters,
            target = relayUrl?.let(RelayTarget::Single) ?: RelayTarget.AllEnabled,
        )
    }

    private fun receivedLikes(
        dates: List<LocalDate>,
        members: Set<String>,
        since: Long,
        until: Long,
        limit: Int,
    ): JournalFetchRequest = JournalFetchRequest(
        dates = dates,
        kinds = setOf(JournalActivityKind.ReceivedLike),
        members = members,
        filters = listOf(
            NostrFilter(
                kinds = listOf(7),
                pTags = members.sorted(),
                since = since,
                until = until,
                limit = limit,
            ),
        ),
        target = RelayTarget.AllEnabled,
    )
}
