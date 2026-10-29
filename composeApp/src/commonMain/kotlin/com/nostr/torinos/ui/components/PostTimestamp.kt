package com.nostr.torinos.ui.components

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 名前の行に出す投稿時刻。今日なら時刻だけ、別の日は日付、別の年は年も付ける。 */
internal fun formatPostTimestamp(
    epochSeconds: Long,
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String = try {
    val local = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(timeZone)
    val today = Instant.fromEpochSeconds(nowEpochSeconds).toLocalDateTime(timeZone).date
    val time = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    val monthDay = "${(local.month.ordinal + 1).toString().padStart(2, '0')}/${local.day.toString().padStart(2, '0')}"
    when {
        local.year != today.year -> "${local.year}/$monthDay $time"
        local.date != today -> "$monthDay $time"
        else -> time
    }
} catch (_: Exception) {
    ""
}

fun formatTimestamp(
    epochSeconds: Long,
    todayTimeOnly: Boolean = false,
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String = try {
    val local = Instant.fromEpochSeconds(epochSeconds)
        .toLocalDateTime(timeZone)
    val timeOnly = if (todayTimeOnly) {
        val today = Instant.fromEpochSeconds(nowEpochSeconds)
            .toLocalDateTime(timeZone)
            .date
        if (local.date == today) {
            local.hour.toString().padStart(2, '0') +
                ":" +
                local.minute.toString().padStart(2, '0')
        } else {
            null
        }
    } else {
        null
    }
    timeOnly ?: buildString {
        append((local.month.ordinal + 1).toString().padStart(2, '0'))
        append('/')
        append(local.day.toString().padStart(2, '0'))
        append(' ')
        append(local.hour.toString().padStart(2, '0'))
        append(':')
        append(local.minute.toString().padStart(2, '0'))
    }
} catch (_: Exception) {
    ""
}
