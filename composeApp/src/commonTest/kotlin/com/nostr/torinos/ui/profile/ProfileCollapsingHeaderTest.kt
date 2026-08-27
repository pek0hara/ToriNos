package com.nostr.torinos.ui.profile

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileCollapsingHeaderTest {
    @Test
    fun progress_staysHiddenBeforeMeasuredCollapseStart() {
        assertEquals(
            expected = 0f,
            actual = calculateProfileCollapseProgress(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 95,
                bannerHeightPx = 176,
                compactHeaderHeightPx = 80,
            ),
        )
    }

    @Test
    fun progress_changesContinuouslyAcrossHeaderHeight() {
        assertEquals(
            expected = 0.5f,
            actual = calculateProfileCollapseProgress(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 136,
                bannerHeightPx = 176,
                compactHeaderHeightPx = 80,
            ),
        )
    }

    @Test
    fun progress_isCompleteAfterBannerLeavesFirstPosition() {
        assertEquals(
            expected = 1f,
            actual = calculateProfileCollapseProgress(
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffset = 0,
                bannerHeightPx = 176,
                compactHeaderHeightPx = 80,
            ),
        )
    }

    @Test
    fun progress_isCompleteWhenRestoredPastHeaderWithoutBannerMeasurement() {
        assertEquals(
            expected = 1f,
            actual = calculateProfileCollapseProgress(
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffset = 0,
                bannerHeightPx = 0,
                compactHeaderHeightPx = 80,
            ),
        )
    }

    @Test
    fun progress_staysExpandedBeforeInitialLayoutMeasurement() {
        assertEquals(
            expected = 0f,
            actual = calculateProfileCollapseProgress(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                bannerHeightPx = 0,
                compactHeaderHeightPx = 80,
            ),
        )
    }
}
