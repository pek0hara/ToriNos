package com.nostr.torinos.ui.post

import com.nostr.torinos.journal.JournalActivityKind
import com.nostr.torinos.journal.JournalCoverage
import com.nostr.torinos.journal.JournalTimeline
import com.nostr.torinos.journal.OTHER
import com.nostr.torinos.journal.OWNER
import com.nostr.torinos.journal.activity
import com.nostr.torinos.journal.daysOfMonthUntil
import com.nostr.torinos.journal.epochOf
import com.nostr.torinos.journal.post
import com.nostr.torinos.journal.reaction
import com.nostr.torinos.journal.reply
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
        members = listOf(OWNER),
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

    @Test
    fun monthModeScrollsToTheLatestEntryOfToday() {
        val timeline = JournalTimeline.Empty.upsert(
            listOf(
                activity(post("old", createdAt = epochOf(LocalDate(2026, 9, 3)))),
                activity(post("today1", createdAt = epochOf(sep15, hour = 8))),
                activity(post("today2", createdAt = epochOf(sep15, hour = 9))),
            ),
        )
        val state = base.copy(timeline = timeline, showCalendar = false).withDerived()
        assertEquals(2, state.todayScrollIndex())
    }

    @Test
    fun monthModeDoesNotScrollPastTodayToFutureEntries() {
        val timeline = JournalTimeline.Empty.upsert(
            listOf(
                activity(post("today1", createdAt = epochOf(sep15, hour = 8))),
                activity(post("today2", createdAt = epochOf(sep15, hour = 9))),
                activity(post("future", createdAt = epochOf(LocalDate(2026, 9, 20)))),
            ),
        )
        val state = base.copy(timeline = timeline, showCalendar = false).withDerived()
        assertEquals(1, state.todayScrollIndex())
    }

    @Test
    fun withoutEntriesTodayMonthModeScrollsToTheLatestDay() {
        val timeline = JournalTimeline.Empty.upsert(
            listOf(
                activity(post("sep3", createdAt = epochOf(LocalDate(2026, 9, 3)))),
                activity(post("sep10a", createdAt = epochOf(LocalDate(2026, 9, 10), hour = 8))),
                activity(post("sep10b", createdAt = epochOf(LocalDate(2026, 9, 10), hour = 9))),
            ),
        )
        val state = base.copy(timeline = timeline, showCalendar = false).withDerived()
        assertEquals(2, state.todayScrollIndex())
    }

    @Test
    fun switchingFromDayViewStartsAtTheRowThatWasOnTop() {
        val sep3 = LocalDate(2026, 9, 3)
        val sep10 = LocalDate(2026, 9, 10)
        val timeline = JournalTimeline.Empty.upsert(
            listOf(
                activity(post("sep3", createdAt = epochOf(sep3))),
                activity(post("sep10a", createdAt = epochOf(sep10, hour = 8))),
                activity(post("sep10b", createdAt = epochOf(sep10, hour = 9))),
            ),
        )
        val month = base.copy(timeline = timeline, showCalendar = false).withDerived()

        assertEquals(2, month.dayAnchorIndex("sep10b", sep10))
        // 記録のない日は、その日以降で最初の行。
        assertEquals(1, month.dayAnchorIndex(null, LocalDate(2026, 9, 5)))
        // 以降に記録がなければ最後の行。
        assertEquals(2, month.dayAnchorIndex(null, LocalDate(2026, 9, 12)))
        assertEquals(null, base.copy(showCalendar = false).withDerived().dayAnchorIndex(null, sep10))
    }

    @Test
    fun noTodayScrollInDayModeOrOtherMonths() {
        val timeline = JournalTimeline.Empty.upsert(listOf(activity(post("a"))))
        assertEquals(null, base.copy(timeline = timeline).withDerived().todayScrollIndex())
        val august = base.copy(
            timeline = timeline,
            showCalendar = false,
            selectedMonth = LocalDate(2026, 8, 1),
            selectedDate = LocalDate(2026, 8, 1),
        ).withDerived()
        assertEquals(null, august.todayScrollIndex())
    }

    @Test
    fun kindCountsOnlyCoverKindsAlreadyFetchedForTheDay() {
        val timeline = JournalTimeline.Empty.upsert(
            listOf(
                activity(post("a")),
                activity(post("b")),
                activity(reply("c", parentId = "x")),
                activity(reaction("d", targetId = "a")),
            ),
        )
        val coverage = JournalCoverage().markLoaded(
            listOf(sep15),
            setOf(JournalActivityKind.Post, JournalActivityKind.Repost, JournalActivityKind.ReceivedLike),
            setOf(OWNER),
        )
        val state = base.copy(timeline = timeline, coverage = coverage).withDerived()

        assertEquals(
            mapOf(
                JournalActivityKind.Post to 2,
                JournalActivityKind.Repost to 0,
                JournalActivityKind.ReceivedLike to 1,
            ),
            state.kindCounts,
        )
    }

    @Test
    fun monthKindCountsNeedEveryDayOfTheMonthFetched() {
        val earlier = LocalDate(2026, 9, 3)
        val timeline = JournalTimeline.Empty.upsert(
            listOf(activity(post("a")), activity(post("b", createdAt = epochOf(earlier)))),
        )
        val partly = JournalCoverage().markLoaded(listOf(sep15), setOf(JournalActivityKind.Post), setOf(OWNER))
        val partlyState = base.copy(timeline = timeline, coverage = partly, showCalendar = false).withDerived()
        assertEquals(emptyMap(), partlyState.kindCounts)

        val whole = JournalCoverage().markLoaded(base.selectedMonth.daysOfMonthUntil(sep15), setOf(JournalActivityKind.Post), setOf(OWNER))
        val wholeState = partlyState.copy(coverage = whole).withDerived()
        assertEquals(mapOf(JournalActivityKind.Post to 2), wholeState.kindCounts)
    }

    @Test
    fun kindCountsOnlyWaitForVisibleMembers() {
        val withHidden = base.copy(
            members = listOf(OWNER, OTHER),
            selectedMembers = emptySet(),
            timeline = JournalTimeline.Empty.upsert(listOf(activity(post("a")))),
            coverage = JournalCoverage().markLoaded(listOf(sep15), setOf(JournalActivityKind.Post), setOf(OWNER)),
        ).withDerived()
        assertEquals(1, withHidden.kindCounts[JournalActivityKind.Post])

        // 選んだ人の分を取り終えるまでは、0件と見せない。
        val shown = withHidden.copy(selectedMembers = setOf(OWNER, OTHER)).withDerived()
        assertEquals(null, shown.kindCounts[JournalActivityKind.Post])
    }

    @Test
    fun fetchingMoreRebuildsKindCounts() {
        val loaded = base.copy(coverage = JournalCoverage().markLoaded(listOf(sep15), setOf(JournalActivityKind.Post), setOf(OWNER)))
        assertTrue(needsDerivedUpdate(base, loaded))
    }

    @Test
    fun showingTodayDependsOnTheMode() {
        assertTrue(base.isShowingToday)
        assertFalse(base.copy(selectedDate = LocalDate(2026, 9, 3)).isShowingToday)
        assertTrue(base.copy(selectedDate = LocalDate(2026, 9, 3), showCalendar = false).isShowingToday)
        assertFalse(base.copy(selectedMonth = LocalDate(2026, 8, 1), showCalendar = false).isShowingToday)
    }

    @Test
    fun dayTitleUsesTheJapaneseWeekday() {
        assertEquals("9月15日（火）", journalDayTitle(sep15))
        assertEquals("10月4日（日）", journalDayTitle(LocalDate(2026, 10, 4)))
    }

    @Test
    fun collapsedCalendarShowsTheWeekOfTheSelectedDate() {
        val weeks = calendarWeeks(LocalDate(2026, 10, 1))
        assertEquals(
            (4..10).map { LocalDate(2026, 10, it) },
            calendarWeekContaining(weeks, LocalDate(2026, 10, 5)),
        )
        assertEquals(listOf(null, null, null, null) + (1..3).map { LocalDate(2026, 10, it) }, calendarWeekContaining(weeks, LocalDate(2026, 10, 2)))
        assertEquals(weeks.first(), calendarWeekContaining(weeks, LocalDate(2026, 9, 30)))
    }
}
