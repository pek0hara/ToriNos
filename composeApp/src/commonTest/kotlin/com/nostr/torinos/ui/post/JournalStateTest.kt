package com.nostr.torinos.ui.post

import com.nostr.torinos.journal.JournalTimeline
import com.nostr.torinos.journal.activity
import com.nostr.torinos.journal.epochOf
import com.nostr.torinos.journal.post
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JournalStateTest {
    private val sep15 = LocalDate(2026, 9, 15)
    private val base = JournalState(
        isSelf = true,
        today = sep15,
        selectedMonth = LocalDate(2026, 9, 1),
        selectedDate = sep15,
    )

    @Test
    fun addingAnotherDayKeepsTheSelectedDaysListInstance() {
        val first = base.copy(timeline = JournalTimeline.Empty.upsert(listOf(activity(post("a"))))).withDerived()
        val otherDay = activity(post("b", createdAt = epochOf(LocalDate(2026, 9, 3))))
        val second = first.copy(timeline = first.timeline.upsert(listOf(otherDay))).withDerived()

        assertSame(first.visibleEntries, second.visibleEntries)
        assertEquals(2, second.entryCountsByDate.size)
    }

    @Test
    fun onlyTimelineKindsSelectionAndModeChangesRebuildDerivedValues() {
        assertFalse(needsDerivedUpdate(base, base.copy(isLoading = true, profiles = emptyMap())))
        assertTrue(needsDerivedUpdate(base, base.copy(showCalendar = false)))
        assertTrue(needsDerivedUpdate(base, base.copy(timeline = JournalTimeline.Empty.upsert(listOf(activity(post("a")))))))
    }

    @Test
    fun monthModeShowsTheWholeMonth() {
        val timeline = JournalTimeline.Empty.upsert(
            listOf(activity(post("a")), activity(post("b", createdAt = epochOf(LocalDate(2026, 9, 3))))),
        )
        val state = base.copy(timeline = timeline, showCalendar = false).withDerived()
        assertEquals(listOf("b", "a"), state.visibleEntries.map { it.event.id })
    }
}
