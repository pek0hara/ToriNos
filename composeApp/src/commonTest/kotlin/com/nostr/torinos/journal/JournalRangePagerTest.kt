package com.nostr.torinos.journal

import com.nostr.torinos.journal.JournalActivityKind.Post
import com.nostr.torinos.journal.JournalActivityKind.Reply
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalRangePagerTest {
    private val clock = utcClock()
    private val sep1 = LocalDate(2026, 9, 1)
    private val sep2 = LocalDate(2026, 9, 2)
    private val sep3 = LocalDate(2026, 9, 3)
    private val sep4 = LocalDate(2026, 9, 4)

    private fun request(): JournalFetchRequest = JournalFetchPlanner.forRange(
        listOf(sep1, sep2, sep3, sep4), JournalCoverage(), setOf(Post), setOf(OWNER), "wss://memo", clock,
    ).single()

    private fun result(request: JournalFetchRequest, vararg events: NostrEvent, truncatedAt: Long? = null, complete: Boolean = true) =
        JournalFetchResult(request, events.toList(), complete, truncatedAt)

    @Test
    fun aPageThatWasNotCutOffClosesEveryDay() {
        val pager = JournalRangePager(request(), clock)
        val page = pager.next()!!
        val note = post("a", createdAt = epochOf(sep2))

        val outcome = pager.accept(result(page, note))

        assertEquals(listOf(sep1, sep2, sep3, sep4), outcome.closedDates)
        assertEquals(listOf(note), outcome.events)
        assertNull(pager.next())
        assertTrue(pager.leftovers().isEmpty())
    }

    @Test
    fun aCutOffPageHoldsTheBoundaryDayAndAsksForOlderEvents() {
        val pager = JournalRangePager(request(), clock)
        val newest = post("newest", createdAt = epochOf(sep4))
        val boundary = post("boundary", createdAt = epochOf(sep2, hour = 15))

        val first = pager.accept(result(pager.next()!!, newest, boundary, truncatedAt = boundary.createdAt))

        assertEquals(listOf(sep3, sep4), first.closedDates)
        assertEquals(listOf(newest), first.events)
        val second = pager.next()!!
        assertEquals(listOf(sep1, sep2), second.dates)
        assertTrue(second.filters.all { it.until == boundary.createdAt })

        // 境目の秒のイベントはもう一度届く。同じ日の古いイベントと合わせて、その日が取り切れたときに返す。
        val older = post("older", createdAt = epochOf(sep2, hour = 9))
        val rest = pager.accept(result(second, boundary, older))

        assertEquals(listOf(sep1, sep2), rest.closedDates)
        assertEquals(setOf("boundary", "older"), rest.events.map { it.id }.toSet())
        assertNull(pager.next())
    }

    @Test
    fun anIncompletePageStopsAndLeavesItsEventsUnrecorded() {
        val pager = JournalRangePager(request(), clock)
        val note = post("a", createdAt = epochOf(sep3))

        val outcome = pager.accept(result(pager.next()!!, note, complete = false))

        assertTrue(outcome.closedDates.isEmpty())
        assertNull(pager.next())
        assertEquals(listOf(note), pager.leftovers())
    }

    @Test
    fun stopsWhenTheCutOffDoesNotMoveBack() {
        val pager = JournalRangePager(request(), clock)
        val first = pager.next()!!
        val until = first.filters.first().until!!

        // 同じ秒に上限以上のイベントがあると、続きを頼んでも進まない。
        val crowded = post("crowded", createdAt = until)
        pager.accept(result(first, crowded, truncatedAt = until))

        assertNull(pager.next())
        assertEquals(listOf(crowded), pager.leftovers())
    }

    @Test
    fun stopsAfterTheMaximumNumberOfPages() {
        val pager = JournalRangePager(request(), clock, maxPages = 2)
        pager.accept(result(pager.next()!!, truncatedAt = epochOf(sep3, hour = 20)))
        pager.accept(result(pager.next()!!, truncatedAt = epochOf(sep3, hour = 10)))

        assertNull(pager.next())
    }

    @Test
    fun truncationIsTheLatestOldestEventAmongFiltersThatReachedTheThreshold() {
        val tallies = listOf(
            JournalFilterTally("wss://a", filterIndex = 0, count = 500, oldest = 100),
            JournalFilterTally("wss://a", filterIndex = 1, count = 20, oldest = 50),
            JournalFilterTally("wss://b", filterIndex = 0, count = 300, oldest = 200),
        )

        assertNull(journalTruncatedAt(tallies) { 501 })
        assertEquals(100, journalTruncatedAt(tallies) { relay -> if (relay == "wss://a") 500 else 1_000 })
        assertEquals(200, journalTruncatedAt(tallies) { 300 })
    }

    @Test
    fun thresholdUsesTheRelayLimitOrAConservativeDefault() {
        assertEquals(300, journalPageThreshold(maxLimit = 300, requested = 500))
        assertEquals(500, journalPageThreshold(maxLimit = 5_000, requested = 500))
        // 上限が分からないリレーは、既定の上限が求めた件数より小さいかもしれない。
        assertEquals(100, journalPageThreshold(maxLimit = null, requested = 500))
        assertEquals(50, journalPageThreshold(maxLimit = null, requested = 50))
    }

    @Test
    fun talliesCountEventsPerRelayAndFilterByKind() {
        val filters = JournalFetchPlanner.forRange(
            listOf(sep1), JournalCoverage(), setOf(Post, Reply), setOf(OWNER), null, clock,
        ).single().filters
        val note = post("note", createdAt = 300)
        val older = post("older", createdAt = 100)
        val comment = event("comment", kind = COMMENT_EVENT_KIND, createdAt = 200)

        val tallies = journalFilterTallies(
            listOf("wss://a" to note, "wss://a" to older, "wss://a" to comment, "wss://b" to note),
            filters,
        )

        assertEquals(
            setOf(
                JournalFilterTally("wss://a", 0, count = 2, oldest = 100),
                JournalFilterTally("wss://a", 1, count = 1, oldest = 200),
                JournalFilterTally("wss://b", 0, count = 1, oldest = 300),
            ),
            tallies.toSet(),
        )
    }
}
