package com.nostr.torinos.journal

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JournalDateNavigatorTest {
    private val today = LocalDate(2026, 9, 15)
    private val entries = mapOf(
        LocalDate(2026, 9, 1) to listOf(LocalDate(2026, 9, 5), LocalDate(2026, 9, 10)),
        LocalDate(2026, 8, 1) to listOf(LocalDate(2026, 8, 20)),
    )
    private val datesWithEntries: (LocalDate) -> List<LocalDate> = { entries[it].orEmpty() }

    @Test
    fun previousMovesToTheLatestEarlierEntryThenToTheMonthStart() {
        assertEquals(LocalDate(2026, 9, 10), JournalDateNavigator.previous(LocalDate(2026, 9, 12), datesWithEntries))
        assertEquals(LocalDate(2026, 9, 1), JournalDateNavigator.previous(LocalDate(2026, 9, 5), datesWithEntries))
    }

    @Test
    fun previousFromTheMonthStartMovesToThePreviousMonthsLastEntry() {
        assertEquals(LocalDate(2026, 8, 20), JournalDateNavigator.previous(LocalDate(2026, 9, 1), datesWithEntries))
        assertEquals(LocalDate(2026, 7, 31), JournalDateNavigator.previous(LocalDate(2026, 8, 1)) { emptyList() })
    }

    @Test
    fun nextStopsAtTodayInTheCurrentMonth() {
        assertEquals(LocalDate(2026, 9, 10), JournalDateNavigator.next(LocalDate(2026, 9, 5), today, datesWithEntries))
        assertEquals(today, JournalDateNavigator.next(LocalDate(2026, 9, 10), today, datesWithEntries))
        assertNull(JournalDateNavigator.next(today, today, datesWithEntries))
    }

    @Test
    fun nextFromThePreviousMonthsEndMovesToTheFirstEntryOfTheNextMonth() {
        assertEquals(LocalDate(2026, 8, 31), JournalDateNavigator.next(LocalDate(2026, 8, 20), today, datesWithEntries))
        assertEquals(LocalDate(2026, 9, 5), JournalDateNavigator.next(LocalDate(2026, 8, 31), today, datesWithEntries))
        assertEquals(LocalDate(2026, 9, 1), JournalDateNavigator.next(LocalDate(2026, 8, 31), today) { emptyList() })
    }

    @Test
    fun monthEdgesUseEntriesOrFallBackToTheBoundary() {
        val month = LocalDate(2026, 9, 1)
        assertEquals(LocalDate(2026, 9, 5), JournalDateNavigator.firstInMonthOrStart(month, today, datesWithEntries))
        assertEquals(LocalDate(2026, 9, 10), JournalDateNavigator.lastInMonthOrEnd(month, today, datesWithEntries))
        assertEquals(month, JournalDateNavigator.firstInMonthOrStart(month, today) { emptyList() })
        assertEquals(today, JournalDateNavigator.lastInMonthOrEnd(month, today) { emptyList() })
    }
}
