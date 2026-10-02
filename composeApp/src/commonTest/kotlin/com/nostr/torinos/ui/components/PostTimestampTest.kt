package com.nostr.torinos.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

class PostTimestampTest {
    private fun format(post: String, now: String = "2026-10-02T01:00:00Z", zone: String = "Asia/Tokyo") =
        formatPostTimestamp(Instant.parse(post).epochSeconds, Instant.parse(now).epochSeconds, TimeZone.of(zone))

    @Test fun todayShowsOnlyTime() {
        assertEquals("09:05", format("2026-10-02T00:05:00Z"))
    }
    @Test fun anotherDayThisYearShowsDateAndTime() {
        assertEquals("10/01\n09:05", format("2026-10-01T00:05:00Z"))
    }
    @Test fun anotherYearShowsYearDateAndTime() {
        assertEquals("2025\n10/01\n09:05", format("2025-10-01T00:05:00Z"))
    }
    @Test fun localMidnightControlsDateOmission() {
        assertEquals("01/01\n23:59", format("2026-01-01T14:59:00Z", "2026-01-01T15:00:00Z"))
    }
    @Test fun localYearBoundaryControlsYearOmission() {
        assertEquals("2025\n12/31\n23:59", format("2025-12-31T14:59:00Z", "2025-12-31T15:00:00Z"))
        assertEquals("00:00", format("2025-12-31T15:00:00Z", "2025-12-31T15:05:00Z"))
    }
    @Test fun utcUsesItsOwnDateAndTime() {
        assertEquals("2025\n12/31\n15:00", format("2025-12-31T15:00:00Z", "2026-01-01T00:05:00Z", "UTC"))
    }
}
