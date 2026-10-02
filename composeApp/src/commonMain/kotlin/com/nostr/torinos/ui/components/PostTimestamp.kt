package com.nostr.torinos.ui.components

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** プロフィールアイコンの下に、必要な年・日付・時刻を縦に表示する。 */
internal fun formatPostTimestamp(
    epochSeconds: Long,
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String = try {
    val local = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(timeZone)
    val today = Instant.fromEpochSeconds(nowEpochSeconds).toLocalDateTime(timeZone).date
    buildList {
        if (local.year != today.year) add(local.year.toString())
        if (local.date != today) add("${(local.month.ordinal + 1).toString().padStart(2, '0')}/${local.day.toString().padStart(2, '0')}")
        add("${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}")
    }.joinToString("\n")
} catch (_: Exception) {
    ""
}
