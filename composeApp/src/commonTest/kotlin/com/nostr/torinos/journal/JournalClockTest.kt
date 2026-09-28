package com.nostr.torinos.journal

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class JournalClockTest {
    private val newYork = JournalClock(TimeZone.of("America/New_York")) { Instant.parse("2026-11-20T12:00:00Z") }

    @Test
    fun plusDaysAdvancesOnTheDayDaylightSavingEnds() {
        // 2026-11-01 は夏時間の終わる25時間の日。秒で足すと同じ日に戻っていた。
        assertEquals(LocalDate(2026, 11, 2), LocalDate(2026, 11, 1).plusDays(1))
        assertEquals(LocalDate(2026, 3, 9), LocalDate(2026, 3, 8).plusDays(1))
    }

    @Test
    fun daysOfMonthUntilTerminatesAcrossDaylightSavingChanges() {
        val november = LocalDate(2026, 11, 1).daysOfMonthUntil(today = LocalDate(2026, 11, 30))
        assertEquals(30, november.size)
        assertEquals(LocalDate(2026, 11, 30), november.last())
    }

    @Test
    fun daysOfMonthUntilStopsAtTodayAndIsEmptyForFutureMonths() {
        val today = LocalDate(2026, 9, 15)
        assertEquals(15, LocalDate(2026, 9, 1).daysOfMonthUntil(today).size)
        assertEquals(emptyList(), LocalDate(2026, 10, 1).daysOfMonthUntil(today))
    }

    @Test
    fun endOfDayCoversTheWholeTwentyFiveHourDay() {
        val date = LocalDate(2026, 11, 1)
        assertEquals(25 * 3_600L - 1, newYork.endOfDay(date) - newYork.startOfDay(date))
        assertEquals(date, newYork.dateOf(newYork.endOfDay(date)))
        assertEquals(date.plusDays(1), newYork.dateOf(newYork.endOfDay(date) + 1))
    }

    @Test
    fun monthArithmeticWrapsYears() {
        assertEquals(LocalDate(2027, 1, 1), LocalDate(2026, 12, 20).nextMonth())
        assertEquals(LocalDate(2025, 12, 1), LocalDate(2026, 1, 20).previousMonth())
        assertEquals(LocalDate(2028, 2, 29), LocalDate(2028, 2, 1).monthEnd())
    }

    @Test
    fun todayUsesTheConfiguredZone() {
        val tokyo = JournalClock(TimeZone.of("Asia/Tokyo")) { Instant.parse("2026-09-15T20:00:00Z") }
        assertEquals(LocalDate(2026, 9, 16), tokyo.today())
        assertEquals(LocalDate(2026, 9, 15), utcClock("2026-09-15T20:00:00Z").today())
    }
}
