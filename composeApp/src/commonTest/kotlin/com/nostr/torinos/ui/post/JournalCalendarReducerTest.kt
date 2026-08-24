package com.nostr.torinos.ui.post

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JournalCalendarReducerTest {
    @Test
    fun selectionAndVisibilityArePureStateTransitions() {
        val selected = JournalCalendarReducer.reduce(
            JournalState(),
            JournalCalendarAction.SelectDate(LocalDate(2026, 8, 24)),
        )
        val hidden = JournalCalendarReducer.reduce(
            selected,
            JournalCalendarAction.SetCalendarVisibility(false),
        )

        assertEquals(LocalDate(2026, 8, 24), hidden.selectedDate)
        assertFalse(hidden.showCalendar)
    }
}
