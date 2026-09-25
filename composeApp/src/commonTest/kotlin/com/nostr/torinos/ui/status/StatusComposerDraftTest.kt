package com.nostr.torinos.ui.status

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusComposerDraftTest {
    @Test
    fun validation_requiresContentCustomIdentifierAndFutureExpiration() {
        val base = draft()

        assertTrue(base.isValid(NOW))
        assertFalse(base.copy(content = "   ").isValid(NOW))
        assertFalse(
            base.copy(
                category = StatusCategoryOption.Custom,
                customIdentifier = " ",
            ).isValid(NOW),
        )
        assertFalse(
            base.copy(
                expirationOption = StatusExpirationOption.Custom,
                customExpiration = NOW,
            ).isValid(NOW),
        )
    }

    @Test
    fun submission_normalizesIdentifierReferenceAndTwentyFourHourExpiration() {
        val submission = draft().copy(
            category = StatusCategoryOption.Custom,
            customIdentifier = " coding ",
            expirationOption = StatusExpirationOption.TwentyFourHours,
            referenceUrl = " https://example.com/status ",
        ).submission(NOW)

        assertEquals("coding", submission.identifier)
        assertEquals(NOW + DAY_SECONDS, submission.expiration)
        assertEquals("https://example.com/status", submission.referenceUrl)
    }

    @Test
    fun dateAndTimeConversion_usesProvidedTimeZone() {
        val utc = TimeZone.UTC
        val selectedDateMillis = 1_735_689_600_000L // 2025-01-01T00:00:00Z

        assertEquals(
            1_735_726_500L, // 2025-01-01T10:15:00Z
            customExpirationEpochSeconds(selectedDateMillis, 10, 15, utc),
        )
    }

    private fun draft() = StatusDraft(
        category = StatusCategoryOption.General,
        customIdentifier = "",
        content = "working",
        expirationOption = StatusExpirationOption.NoExpiration,
        customExpiration = NOW + 60,
        referenceUrl = "",
    )

    private companion object {
        const val NOW = 1_000L
    }
}
