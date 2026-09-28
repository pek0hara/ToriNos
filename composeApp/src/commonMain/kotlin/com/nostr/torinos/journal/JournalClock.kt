package com.nostr.torinos.journal

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** ジャーナルの「今日」と日付境界。テストでは固定の時刻とタイムゾーンを渡す。 */
internal class JournalClock(
    val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val now: () -> Instant = { Clock.System.now() },
) {
    fun today(): LocalDate = now().toLocalDateTime(zone).date

    fun nowMillis(): Long = now().toEpochMilliseconds()

    fun currentMonth(): LocalDate = today().monthStart()

    fun dateOf(epochSeconds: Long): LocalDate =
        Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(zone).date

    fun startOfDay(date: LocalDate): Long = date.atStartOfDayIn(zone).epochSeconds

    /** その日の最後の秒。夏時間で長さが変わる日も翌日0時の1秒前になる。 */
    fun endOfDay(date: LocalDate): Long = startOfDay(date.plusDays(1)) - 1
}

internal fun LocalDate.monthStart(): LocalDate = LocalDate(year, month, 1)

internal fun LocalDate.nextMonth(): LocalDate = monthStart().plus(1, DateTimeUnit.MONTH)

internal fun LocalDate.previousMonth(): LocalDate = monthStart().minus(1, DateTimeUnit.MONTH)

internal fun LocalDate.monthEnd(): LocalDate = nextMonth().minusDays(1)

/** 暦の上で日を足す。秒で足すと夏時間の終わる日に同じ日付へ戻るため使わない。 */
internal fun LocalDate.plusDays(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)

internal fun LocalDate.minusDays(days: Int): LocalDate = minus(days, DateTimeUnit.DAY)

internal fun LocalDate.isSameMonth(other: LocalDate): Boolean =
    year == other.year && month == other.month

/** 月初から、月末と今日の早い方までの日付。未来の月は空。 */
internal fun LocalDate.daysOfMonthUntil(today: LocalDate): List<LocalDate> {
    val start = monthStart()
    val last = minOf(start.monthEnd(), today)
    if (start > last) return emptyList()
    return generateSequence(start) { it.plusDays(1) }
        .takeWhile { it <= last }
        .toList()
}
