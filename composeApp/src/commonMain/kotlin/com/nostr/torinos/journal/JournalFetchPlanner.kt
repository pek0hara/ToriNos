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

/**
 * [truncatedAt]が null でなければ、件数の上限に届いて打ち切られた。その時刻より新しいイベントは全部届いたが、
 * その時刻以前には届いていないものがある。
 */
internal data class JournalFetchResult(
    val request: JournalFetchRequest,
    val events: List<NostrEvent>,
    val complete: Boolean,
    val truncatedAt: Long? = null,
)

/** 日付・種類・取得済み範囲から、リレーへ送るフィルターを組み立てる純粋処理。 */
internal object JournalFetchPlanner {
    const val DATE_LIMIT = 500
    /** 範囲の取得の1ページの上限。打ち切られたら`until`を下げて続きを取る。 */
    const val PAGE_LIMIT = 500

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
            .mapNotNull { (kinds, members) ->
                authored(listOf(date), kinds, members.toSet(), relayUrl, since, until, DATE_LIMIT)
            }
        val receivedLikeMembers = missingByMember
            .filterValues { JournalActivityKind.ReceivedLike in it }
            .keys
        val receivedLikeRequest = receivedLikeMembers.takeIf { it.isNotEmpty() }
            ?.let { receivedLikes(listOf(date), it, since, until, DATE_LIMIT) }
        return authoredRequests + listOfNotNull(receivedLikeRequest)
    }

    /**
     * [days]のうち足りない分を、日ごとではなく範囲で取る。足りない種類が同じメンバーを1つのリクエストにまとめ、
     * 範囲はそのメンバーたちに足りない日がある最初の日から最後の日までにする。範囲内の取得済みの日も取り直すが、
     * 結果はIDで重複を除くため害はなく、リクエストを分けるより安い。もらったいいねは取得先が違うため別にする。
     */
    fun forRange(
        days: List<LocalDate>,
        coverage: JournalCoverage,
        kinds: Set<JournalActivityKind>,
        members: Collection<String>,
        relayUrl: String?,
        clock: JournalClock,
    ): List<JournalFetchRequest> {
        val receivedLike = setOf(JournalActivityKind.ReceivedLike)
        val authoredKinds = kinds - JournalActivityKind.ReceivedLike
        val authoredRequests = members
            .associateWith { member -> days.flatMapTo(mutableSetOf()) { coverage.missing(it, authoredKinds, member) } }
            .filterValues { it.isNotEmpty() }
            .entries
            .groupBy({ it.value }, { it.key })
            .mapNotNull { (missingKinds, group) ->
                val range = days.missingRange(coverage, missingKinds, group) ?: return@mapNotNull null
                authored(
                    range, missingKinds, group.toSet(), relayUrl,
                    since = clock.startOfDay(range.first()), until = clock.endOfDay(range.last()), limit = PAGE_LIMIT,
                )
            }
        val receivedLikeMembers = if (JournalActivityKind.ReceivedLike in kinds) {
            members.filterTo(mutableSetOf()) { member -> days.any { coverage.missing(it, receivedLike, member).isNotEmpty() } }
        } else {
            emptySet()
        }
        val receivedLikeRequest = receivedLikeMembers.takeIf { it.isNotEmpty() }?.let { targets ->
            days.missingRange(coverage, receivedLike, targets)?.let { range ->
                receivedLikes(range, targets, clock.startOfDay(range.first()), clock.endOfDay(range.last()), PAGE_LIMIT)
            }
        }
        return authoredRequests + listOfNotNull(receivedLikeRequest)
    }

    /** [members]の誰かに[kinds]が足りない最初の日から最後の日まで。足りない日がなければ null。 */
    private fun List<LocalDate>.missingRange(
        coverage: JournalCoverage,
        kinds: Set<JournalActivityKind>,
        members: Collection<String>,
    ): List<LocalDate>? {
        val first = indexOfFirst { coverage.missingByMember(it, kinds, members).isNotEmpty() }
        if (first < 0) return null
        val last = indexOfLast { coverage.missingByMember(it, kinds, members).isNotEmpty() }
        return subList(first, last + 1).toList()
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
        limit: Int,
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
                        limit = limit,
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
                        limit = limit,
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
