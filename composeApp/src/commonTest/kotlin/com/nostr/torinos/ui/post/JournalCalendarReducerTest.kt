package com.nostr.torinos.ui.post

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JournalCalendarReducerTest {
    @Test
    fun selectionAndVisibilityArePureStateTransitions() {
        val content = JournalContent()
        val selected = JournalCalendarReducer.reduce(
            JournalState(content = content),
            JournalCalendarAction.SelectDate(LocalDate(2026, 8, 24)),
        )
        val hidden = JournalCalendarReducer.reduce(
            selected,
            JournalCalendarAction.SetCalendarVisibility(false),
        )

        assertEquals(LocalDate(2026, 8, 24), hidden.selectedDate)
        assertFalse(hidden.showCalendar)
        assertSame(content, hidden.content)
    }

    @Test
    fun loadedMonthRequiresEveryDateAndRequestedKind() {
        val month = LocalDate(2026, 8, 1)
        val today = LocalDate(2026, 8, 3)
        val loaded = JournalState(
            loadedKindsByDate = mapOf(
                LocalDate(2026, 8, 1) to setOf(JournalLoadKind.Post, JournalLoadKind.Reply),
                LocalDate(2026, 8, 2) to setOf(JournalLoadKind.Post, JournalLoadKind.Reply),
                LocalDate(2026, 8, 3) to setOf(JournalLoadKind.Post, JournalLoadKind.Reply),
            ),
        )

        assertTrue(loaded.hasLoadedMonth(month, setOf(JournalLoadKind.Post), today))
        assertFalse(loaded.hasLoadedMonth(month, setOf(JournalLoadKind.Memo), today))
        assertFalse(
            loaded.copy(
                loadedKindsByDate = loaded.loadedKindsByDate - LocalDate(2026, 8, 2),
            ).hasLoadedMonth(month, setOf(JournalLoadKind.Post), today),
        )
    }
}
