package com.nostr.torinos.journal

import com.nostr.torinos.model.NostrEvent
import kotlinx.datetime.LocalDate

/**
 * メンバーごと・日付ごとに、どの種類を全リレーから取得し終えたか。
 * 表示中のメンバーだけを取得するため、誰の分まで取ったかをメンバー単位で持つ。
 */
data class JournalCoverage(
    val byMember: Map<String, Map<LocalDate, Set<JournalActivityKind>>> = emptyMap(),
) {
    /** 取得済みの日数の合計。ログ用。 */
    val coveredDays: Int get() = byMember.values.sumOf { it.size }

    fun missing(date: LocalDate, kinds: Set<JournalActivityKind>, member: String): Set<JournalActivityKind> =
        kinds - byMember[member]?.get(date).orEmpty()

    /** [members]それぞれの、[date]でまだ取得していない種類。足りないものがない人は含めない。 */
    fun missingByMember(
        date: LocalDate,
        kinds: Set<JournalActivityKind>,
        members: Collection<String>,
    ): Map<String, Set<JournalActivityKind>> =
        members.associateWith { missing(date, kinds, it) }.filterValues { it.isNotEmpty() }

    /** [kinds]のうち、[members]全員が[date]で取得し終えた種類。 */
    fun loadedKinds(
        date: LocalDate,
        kinds: Set<JournalActivityKind>,
        members: Collection<String>,
    ): Set<JournalActivityKind> =
        kinds.filterTo(mutableSetOf()) { kind -> members.all { kind !in missing(date, setOf(kind), it) } }

    /** 月初から、月末と今日の早い方までのすべての日で、[members]全員が[kinds]を取得済みか。 */
    fun hasLoadedMonth(
        month: LocalDate,
        kinds: Set<JournalActivityKind>,
        today: LocalDate,
        members: Collection<String>,
    ): Boolean {
        val monthStart = month.monthStart()
        if (monthStart > today.monthStart()) return false
        val days = monthStart.daysOfMonthUntil(today)
        return members.all { member -> days.all { date -> missing(date, kinds, member).isEmpty() } }
    }

    fun markLoaded(
        dates: Collection<LocalDate>,
        kinds: Set<JournalActivityKind>,
        members: Collection<String>,
    ): JournalCoverage {
        if (dates.isEmpty() || kinds.isEmpty() || members.isEmpty()) return this
        val updated = byMember.toMutableMap()
        members.forEach { member ->
            val byDate = updated[member].orEmpty().toMutableMap()
            dates.forEach { date -> byDate[date] = byDate[date].orEmpty() + kinds }
            updated[member] = byDate
        }
        return JournalCoverage(updated)
    }

    fun forgetDate(date: LocalDate): JournalCoverage =
        JournalCoverage(byMember.mapValues { (_, byDate) -> byDate - date })

    fun forgetMonth(month: LocalDate): JournalCoverage =
        JournalCoverage(byMember.mapValues { (_, byDate) -> byDate.filterKeys { !it.isSameMonth(month) } })

    /** 外したメンバーの記録を消す。また追加したときは取り直す。 */
    fun forgetMembers(members: Collection<String>): JournalCoverage =
        if (members.none { it in byMember }) this else JournalCoverage(byMember - members.toSet())
}

/**
 * 分類済みのアクティビティ。分類は挿入時に一度だけ行う。
 * [roles]はメンバーごとの種類。同じイベントが複数のメンバーに当たっても1件として扱う。
 */
data class JournalActivity(
    val event: NostrEvent,
    val date: LocalDate,
    val roles: Map<String, Set<JournalActivityKind>>,
) {
    /** 全メンバー分の種類。 */
    val kinds: Set<JournalActivityKind> = roles.values.flatMapTo(mutableSetOf()) { it }

    /** [members]（null なら全員）に当たる種類。 */
    fun kindsFor(members: Set<String>?): Set<JournalActivityKind> =
        if (members == null) kinds else roles.filterKeys { it in members }.values.flatMapTo(mutableSetOf()) { it }

    fun matches(selected: Set<JournalActivityKind>, members: Set<String>? = null): Boolean =
        if (members == null) {
            kinds.any { it in selected }
        } else {
            roles.any { (pubkey, kinds) -> pubkey in members && kinds.any { it in selected } }
        }
}

/**
 * 取得したアクティビティの日付索引。
 * 投稿本体の変化でだけ作り直し、リアクションや表示状態の更新では作り直さない。
 * 更新時は変化した日付の並びだけを作り直す。
 */
class JournalTimeline private constructor(
    private val byId: Map<String, JournalActivity>,
    private val idsByDate: Map<LocalDate, List<String>>,
) {
    val size: Int get() = byId.size

    fun isEmpty(): Boolean = byId.isEmpty()

    operator fun get(eventId: String): JournalActivity? = byId[eventId]

    fun upsert(activities: Collection<JournalActivity>): JournalTimeline {
        if (activities.isEmpty()) return this
        val nextById = byId.toMutableMap()
        val touchedDates = mutableSetOf<LocalDate>()
        activities.forEach { activity ->
            nextById.put(activity.event.id, activity)?.let { previous -> touchedDates += previous.date }
            touchedDates += activity.date
        }
        return rebuildDates(nextById, touchedDates, activities.map { it.event.id })
    }

    fun remove(eventId: String): JournalTimeline {
        val removed = byId[eventId] ?: return this
        return rebuildDates(byId - eventId, setOf(removed.date))
    }

    /**
     * [date]で、[members]の[kinds]に該当するアクティビティを除く。更新結果で置き換える前に使う。
     * ほかのメンバーにも当たる行も除くが、同じイベントなら取得結果に含まれて戻る。
     */
    fun removeMatching(
        date: LocalDate,
        kinds: Set<JournalActivityKind>,
        members: Set<String>? = null,
    ): JournalTimeline {
        val ids = idsByDate[date].orEmpty().filter { id -> byId.getValue(id).matches(kinds, members) }
        if (ids.isEmpty()) return this
        return rebuildDates(byId - ids.toSet(), setOf(date))
    }

    /**
     * メンバーが変わったときに、取得済みの行の種類を[classify]で判定し直す。誰にも当たらなくなった行は除く。
     * 通信はせず、手元の行だけで済ませる。
     */
    fun reclassify(classify: (NostrEvent) -> Map<String, Set<JournalActivityKind>>): JournalTimeline {
        if (byId.isEmpty()) return this
        val nextById = byId.mapValues { (_, activity) -> activity.copy(roles = classify(activity.event)) }
            .filterValues { it.roles.isNotEmpty() }
        val removedDates = byId.filterKeys { it !in nextById }.values.mapTo(mutableSetOf()) { it.date }
        if (removedDates.isEmpty()) return JournalTimeline(nextById, idsByDate)
        return rebuildDates(nextById, removedDates)
    }

    /** [members]が null なら全メンバー、そうでなければそのメンバーに当たるものだけを返す。 */
    fun entries(
        date: LocalDate,
        kinds: Set<JournalActivityKind>,
        members: Set<String>? = null,
    ): List<JournalActivity> =
        idsByDate[date].orEmpty().mapNotNull { id -> byId[id]?.takeIf { it.matches(kinds, members) } }

    fun monthEntries(
        month: LocalDate,
        kinds: Set<JournalActivityKind>,
        members: Set<String>? = null,
    ): List<JournalActivity> =
        idsByDate.keys
            .filter { it.isSameMonth(month) }
            .sorted()
            .flatMap { date -> entries(date, kinds, members) }

    fun countsByDate(
        month: LocalDate,
        kinds: Set<JournalActivityKind>,
        members: Set<String>? = null,
    ): Map<LocalDate, Int> =
        idsByDate.keys
            .filter { it.isSameMonth(month) }
            .associateWith { date -> entries(date, kinds, members).size }
            .filterValues { it > 0 }

    fun datesWithEntries(
        month: LocalDate,
        kinds: Set<JournalActivityKind>,
        members: Set<String>? = null,
    ): List<LocalDate> =
        countsByDate(month, kinds, members).keys.sorted()

    fun eventIdsOn(dates: Collection<LocalDate>): Set<String> =
        dates.flatMapTo(mutableSetOf()) { idsByDate[it].orEmpty() }

    private fun rebuildDates(
        nextById: Map<String, JournalActivity>,
        dates: Set<LocalDate>,
        addedIds: List<String> = emptyList(),
    ): JournalTimeline {
        val nextIdsByDate = idsByDate.toMutableMap()
        dates.forEach { date -> nextIdsByDate.remove(date) }
        // 対象日の既存IDと追加分だけを見る。全件走査はしない。
        (dates.flatMap { idsByDate[it].orEmpty() } + addedIds)
            .distinct()
            .mapNotNull { nextById[it] }
            .filter { it.date in dates }
            .groupBy { it.date }
            .forEach { (date, activities) ->
                nextIdsByDate[date] = activities
                    .sortedWith(compareBy<JournalActivity> { it.event.createdAt }.thenBy { it.event.id })
                    .map { it.event.id }
            }
        return JournalTimeline(nextById, nextIdsByDate)
    }

    companion object {
        val Empty = JournalTimeline(emptyMap(), emptyMap())
    }
}
