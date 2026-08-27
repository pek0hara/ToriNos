package com.nostr.torinos.ui.feed

import kotlin.test.Test
import kotlin.test.assertEquals

class ResumeSyncStrategyTest {
    @Test
    fun noKnownEventFetchesOnlyLatestPage() {
        assertEquals(
            ResumeSyncStrategy.LatestPage,
            resumeSyncStrategy(gapSince = null, nowSec = 100),
        )
    }

    @Test
    fun shortBackgroundGapDoesNotFetchHistory() {
        assertEquals(
            ResumeSyncStrategy.None,
            resumeSyncStrategy(gapSince = 95, nowSec = 100),
        )
    }

    @Test
    fun gapWithinOneDayIsFilled() {
        assertEquals(
            ResumeSyncStrategy.GapFill,
            resumeSyncStrategy(gapSince = 100, nowSec = 100 + 24 * 60 * 60),
        )
    }

    @Test
    fun gapLongerThanOneDayFetchesOnlyLatestPage() {
        assertEquals(
            ResumeSyncStrategy.LatestPage,
            resumeSyncStrategy(gapSince = 100, nowSec = 101 + 24 * 60 * 60),
        )
    }

    @Test
    fun futureTimestampDoesNotCauseHistoryFetch() {
        assertEquals(
            ResumeSyncStrategy.None,
            resumeSyncStrategy(gapSince = 110, nowSec = 100),
        )
    }
}
