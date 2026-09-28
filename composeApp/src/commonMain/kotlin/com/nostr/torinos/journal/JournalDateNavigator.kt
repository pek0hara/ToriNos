package com.nostr.torinos.journal

import kotlinx.datetime.LocalDate

/**
 * 前後の日付移動。選択中の種類のアクティビティがある日へ移り、
 * 当月内になければ月初・月末（当月は今日）で止まる。次の操作で隣の月へ移る。
 * [datesWithEntries]は月初を受け取り、その月でアクティビティがある日を返す。
 */
internal object JournalDateNavigator {
    fun previous(
        selected: LocalDate,
        datesWithEntries: (LocalDate) -> List<LocalDate>,
    ): LocalDate {
        val monthStart = selected.monthStart()
        datesWithEntries(monthStart).filter { it < selected }.maxOrNull()?.let { return it }
        if (selected > monthStart) return monthStart

        val previousMonth = monthStart.previousMonth()
        return lastInMonthOrEnd(previousMonth, previousMonth.monthEnd(), datesWithEntries)
    }

    /** 今日より先へは進めない。今日を選んでいれば null。 */
    fun next(
        selected: LocalDate,
        today: LocalDate,
        datesWithEntries: (LocalDate) -> List<LocalDate>,
    ): LocalDate? {
        val monthStart = selected.monthStart()
        val monthLast = minOf(monthStart.monthEnd(), today)
        datesWithEntries(monthStart).filter { it > selected && it <= today }.minOrNull()?.let { return it }
        if (selected < monthLast) return monthLast
        if (monthLast == today) return null

        val nextMonth = monthStart.nextMonth()
        return firstInMonthOrStart(nextMonth, today, datesWithEntries)
    }

    fun firstInMonthOrStart(
        month: LocalDate,
        today: LocalDate,
        datesWithEntries: (LocalDate) -> List<LocalDate>,
    ): LocalDate {
        val last = minOf(month.monthEnd(), today)
        return datesWithEntries(month).filter { it <= last }.minOrNull() ?: month
    }

    fun lastInMonthOrEnd(
        month: LocalDate,
        today: LocalDate,
        datesWithEntries: (LocalDate) -> List<LocalDate>,
    ): LocalDate {
        val last = minOf(month.monthEnd(), today)
        return datesWithEntries(month).filter { it <= last }.maxOrNull() ?: last
    }
}
