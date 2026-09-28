package com.nostr.torinos.journal

import com.nostr.torinos.model.NostrEvent
import kotlinx.datetime.LocalDate

/** 日付ごとに、どの種類を全リレーから取得し終えたか。 */
data class JournalCoverage(
    val byDate: Map<LocalDate, Set<JournalActivityKind>> = emptyMap(),
) {
    fun missing(date: LocalDate, kinds: Set<JournalActivityKind>): Set<JournalActivityKind> =
        kinds - byDate[date].orEmpty()

    /** 月初から、月末と今日の早い方までのすべての日で[kinds]を取得済みか。 */
    fun hasLoadedMonth(month: LocalDate, kinds: Set<JournalActivityKind>, today: LocalDate): Boolean {
        val monthStart = month.monthStart()
        if (monthStart > today.monthStart()) return false
        return monthStart.daysOfMonthUntil(today).all { date -> byDate[date].orEmpty().containsAll(kinds) }
    }

    fun markLoaded(dates: Collection<LocalDate>, kinds: Set<JournalActivityKind>): JournalCoverage {
        if (dates.isEmpty() || kinds.isEmpty()) return this
        val updated = byDate.toMutableMap()
        dates.forEach { date -> updated[date] = updated[date].orEmpty() + kinds }
        return JournalCoverage(updated)
    }

    fun forgetDate(date: LocalDate): JournalCoverage =
        if (date in byDate) JournalCoverage(byDate - date) else this

    fun forgetMonth(month: LocalDate): JournalCoverage =
        JournalCoverage(byDate.filterKeys { !it.isSameMonth(month) })
}

/** 分類済みのアクティビティ。分類は挿入時に一度だけ行う。 */
data class JournalActivity(
    val event: NostrEvent,
    val date: LocalDate,
    val kinds: Set<JournalActivityKind>,
) {
    fun matches(selected: Set<JournalActivityKind>): Boolean = kinds.any { it in selected }
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

    /** [date]の[kinds]に該当するアクティビティを除く。更新結果で置き換える前に使う。 */
    fun removeMatching(date: LocalDate, kinds: Set<JournalActivityKind>): JournalTimeline {
        val ids = idsByDate[date].orEmpty().filter { id -> byId.getValue(id).matches(kinds) }
        if (ids.isEmpty()) return this
        return rebuildDates(byId - ids.toSet(), setOf(date))
    }

    fun entries(date: LocalDate, kinds: Set<JournalActivityKind>): List<JournalActivity> =
        idsByDate[date].orEmpty().mapNotNull { id -> byId[id]?.takeIf { it.matches(kinds) } }

    fun monthEntries(month: LocalDate, kinds: Set<JournalActivityKind>): List<JournalActivity> =
        idsByDate.keys
            .filter { it.isSameMonth(month) }
            .sorted()
            .flatMap { date -> entries(date, kinds) }

    fun countsByDate(month: LocalDate, kinds: Set<JournalActivityKind>): Map<LocalDate, Int> =
        idsByDate.keys
            .filter { it.isSameMonth(month) }
            .associateWith { date -> entries(date, kinds).size }
            .filterValues { it > 0 }

    fun datesWithEntries(month: LocalDate, kinds: Set<JournalActivityKind>): List<LocalDate> =
        countsByDate(month, kinds).keys.sorted()

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
