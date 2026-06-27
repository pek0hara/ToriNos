package com.nostr.torinos.ui.components

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class NoteCardTimestampTest {
    @Test
    fun formatTimestamp_todayTimeOnlyShowsOnlyTimeForToday() {
        assertEquals(
            "12:34",
            formatTimestamp(
                epochSeconds = 45_296,
                todayTimeOnly = true,
                nowEpochSeconds = 45_296,
                timeZone = TimeZone.UTC,
            ),
        )
    }

    @Test
    fun formatTimestamp_todayTimeOnlyKeepsDateForAnotherDay() {
        assertEquals(
            "01/01 12:34",
            formatTimestamp(
                epochSeconds = 45_296,
                todayTimeOnly = true,
                nowEpochSeconds = 131_696,
                timeZone = TimeZone.UTC,
            ),
        )
    }
}
