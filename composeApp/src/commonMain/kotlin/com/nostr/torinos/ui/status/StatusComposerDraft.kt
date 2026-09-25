package com.nostr.torinos.ui.status

import com.nostr.torinos.status.GENERAL_STATUS_IDENTIFIER
import com.nostr.torinos.status.MUSIC_STATUS_IDENTIFIER
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

internal data class StatusDraft(
    val category: StatusCategoryOption,
    val customIdentifier: String,
    val content: String,
    val expirationOption: StatusExpirationOption,
    val customExpiration: Long,
    val referenceUrl: String,
) {
    fun isValid(nowEpochSeconds: Long): Boolean =
        content.isNotBlank() &&
            (category != StatusCategoryOption.Custom || customIdentifier.isNotBlank()) &&
            (expirationOption != StatusExpirationOption.Custom || customExpiration > nowEpochSeconds)

    fun submission(nowEpochSeconds: Long): StatusSubmission = StatusSubmission(
        identifier = category.identifier(customIdentifier),
        content = content,
        expiration = when (expirationOption) {
            StatusExpirationOption.TwentyFourHours -> nowEpochSeconds + DAY_SECONDS
            StatusExpirationOption.Custom -> customExpiration
            StatusExpirationOption.NoExpiration -> null
        },
        referenceUrl = referenceUrl.trim(),
    )
}

internal data class StatusSubmission(
    val identifier: String,
    val content: String,
    val expiration: Long?,
    val referenceUrl: String,
)

internal data class StatusComposerValue(
    val content: String = "",
    val expiration: Long? = null,
    val referenceUrl: String = "",
)

internal enum class StatusCategoryOption(
    val label: String,
    private val fixedIdentifier: String?,
) {
    General("💬 一般", GENERAL_STATUS_IDENTIFIER),
    Music("♫ 音楽", MUSIC_STATUS_IDENTIFIER),
    Custom("その他", null);

    fun identifier(customIdentifier: String): String = fixedIdentifier ?: customIdentifier.trim()

    companion object {
        fun forIdentifier(identifier: String): StatusCategoryOption = when (identifier) {
            GENERAL_STATUS_IDENTIFIER -> General
            MUSIC_STATUS_IDENTIFIER -> Music
            else -> Custom
        }
    }
}

internal enum class StatusExpirationOption(val label: String) {
    TwentyFourHours("24時間後"),
    Custom("カスタム"),
    NoExpiration("終了なし"),
}

internal fun Long.toUtcDateMillis(): Long {
    val date = Instant.fromEpochSeconds(this)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
    return LocalDateTime(date.year, date.month, date.day, 0, 0)
        .toInstant(TimeZone.UTC)
        .toEpochMilliseconds()
}

internal fun customExpirationEpochSeconds(
    selectedDateMillis: Long,
    hour: Int,
    minute: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Long {
    val date: LocalDate = Instant.fromEpochMilliseconds(selectedDateMillis)
        .toLocalDateTime(TimeZone.UTC)
        .date
    return LocalDateTime(date.year, date.month, date.day, hour, minute)
        .toInstant(timeZone)
        .epochSeconds
}

internal const val DAY_SECONDS = 24 * 60 * 60L
