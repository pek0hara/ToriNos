package com.nostr.torinos.ui.feed

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedResumePolicyTest {
    @Test
    fun backgroundUpToTenMinutesKeepsCurrentTimeline() {
        assertFalse(
            shouldResetFeedAfterBackground(
                backgroundedAtMillis = 1_000L,
                foregroundedAtMillis = 1_000L + FEED_HARD_RESET_BACKGROUND_MILLIS,
            ),
        )
    }

    @Test
    fun backgroundLongerThanTenMinutesResetsTimeline() {
        assertTrue(
            shouldResetFeedAfterBackground(
                backgroundedAtMillis = 1_000L,
                foregroundedAtMillis = 1_001L + FEED_HARD_RESET_BACKGROUND_MILLIS,
            ),
        )
    }

    @Test
    fun backwardClockChangeDoesNotResetTimeline() {
        assertFalse(
            shouldResetFeedAfterBackground(
                backgroundedAtMillis = 2_000L,
                foregroundedAtMillis = 1_000L,
            ),
        )
    }
}
