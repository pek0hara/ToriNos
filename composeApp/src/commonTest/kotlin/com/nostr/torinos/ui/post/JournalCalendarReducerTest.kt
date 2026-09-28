package com.nostr.torinos.ui.post

import com.nostr.torinos.journal.JournalTimeline
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class JournalCalendarReducerTest {
    @Test
    fun selectionAndVisibilityArePureStateTransitions() {
        val today = LocalDate(2026, 9, 15)
        val timeline = JournalTimeline.Empty
        val initial = JournalState(
            isSelf = true,
            today = today,
            selectedMonth = LocalDate(2026, 9, 1),
            selectedDate = today,
            timeline = timeline,
        )
        val selected = JournalCalendarReducer.reduce(
            initial,
            JournalCalendarAction.SelectDate(LocalDate(2026, 8, 24)),
        )
        val hidden = JournalCalendarReducer.reduce(
            selected,
            JournalCalendarAction.SetCalendarVisibility(false),
        )

        assertEquals(LocalDate(2026, 8, 24), hidden.selectedDate)
        assertFalse(hidden.showCalendar)
        assertSame(timeline, hidden.timeline)
    }
}
