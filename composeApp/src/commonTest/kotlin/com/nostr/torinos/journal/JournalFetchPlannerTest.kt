package com.nostr.torinos.journal

import com.nostr.torinos.journal.JournalActivityKind.Like
import com.nostr.torinos.journal.JournalActivityKind.Post
import com.nostr.torinos.journal.JournalActivityKind.ReceivedLike
import com.nostr.torinos.journal.JournalActivityKind.Reply
import com.nostr.torinos.journal.JournalActivityKind.Repost
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.network.RelayTarget
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalFetchPlannerTest {
    private val clock = utcClock()
    private val date = LocalDate(2026, 9, 15)
    private val month = LocalDate(2026, 9, 1)
    private val today = LocalDate(2026, 9, 15)

    @Test
    fun authoredKindsGoToTheSelectedRelayWithCommentsInTheirOwnFilter() {
        val requests = JournalFetchPlanner.forDate(date, setOf(Post, Reply, Repost), SelfOwner, "wss://memo", clock)

        val authored = requests.single()
        assertEquals(RelayTarget.Single("wss://memo"), authored.target)
        assertEquals(setOf(Post, Reply, Repost), authored.kinds)
        assertEquals(listOf(date), authored.dates)
        val (notes, comments) = authored.filters
        assertEquals(listOf(1, 6), notes.kinds)
        assertEquals(listOf(OWNER), notes.authors)
        assertEquals(clock.startOfDay(date), notes.since)
        assertEquals(clock.endOfDay(date), notes.until)
        assertEquals(listOf(COMMENT_EVENT_KIND), comments.kinds)
        assertEquals(listOf("1"), comments.rootKindTags)
    }

    @Test
    fun receivedLikesAreFetchedFromAllRelaysSeparately() {
        val requests = JournalFetchPlanner.forDate(date, setOf(Post, ReceivedLike), SelfOwner, "wss://memo", clock)

        assertEquals(2, requests.size)
        val received = requests.single { ReceivedLike in it.kinds }
        assertEquals(RelayTarget.AllEnabled, received.target)
        assertEquals(listOf(7), received.filters.single().kinds)
        assertEquals(listOf(OWNER), received.filters.single().pTags)
        assertEquals(setOf(Post), requests.single { it !== received }.kinds)
    }

    @Test
    fun withoutASelectedRelayAuthoredKindsUseAllRelays() {
        val request = JournalFetchPlanner.forDate(date, setOf(Post), SelfOwner, null, clock).single()
        assertEquals(RelayTarget.AllEnabled, request.target)
    }

    @Test
    fun likesAreOnlyFetchedForOwnJournal() {
        assertEquals(listOf(7), JournalFetchPlanner.forDate(date, setOf(Like), SelfOwner, null, clock).single().filters.single().kinds)
        assertTrue(JournalFetchPlanner.forDate(date, setOf(Like), UserOwner, null, clock).isEmpty())
    }

    @Test
    fun nothingToFetchForNoKinds() {
        assertTrue(JournalFetchPlanner.forDate(date, emptySet(), SelfOwner, null, clock).isEmpty())
    }

    @Test
    fun monthReceivedLikesCoverOnlyMissingDatesUpToToday() {
        val coverage = JournalCoverage().markLoaded(listOf(LocalDate(2026, 9, 1)), setOf(ReceivedLike))

        val request = JournalFetchPlanner.monthReceivedLikes(month, today, setOf(ReceivedLike), coverage, SelfOwner, clock)!!

        assertEquals(14, request.dates.size)
        assertEquals(LocalDate(2026, 9, 2), request.dates.first())
        assertEquals(today, request.dates.last())
        assertEquals(clock.startOfDay(month), request.filters.single().since)
        assertEquals(clock.endOfDay(today), request.filters.single().until)
        assertEquals(JournalFetchPlanner.MONTH_RECEIVED_LIKE_LIMIT, request.filters.single().limit)
    }

    @Test
    fun monthReceivedLikesAreSkippedWhenLoadedOrNotSelected() {
        val loaded = JournalCoverage().markLoaded(month.daysOfMonthUntil(today), setOf(ReceivedLike))
        assertNull(JournalFetchPlanner.monthReceivedLikes(month, today, setOf(ReceivedLike), loaded, SelfOwner, clock))
        assertNull(JournalFetchPlanner.monthReceivedLikes(month, today, setOf(Post), JournalCoverage(), SelfOwner, clock))
        assertFalse(JournalFetchPlanner.willFetchMonthReceivedLikes(month, today, setOf(ReceivedLike), loaded))
        assertTrue(JournalFetchPlanner.willFetchMonthReceivedLikes(month, today, setOf(ReceivedLike), JournalCoverage()))
    }

    @Test
    fun requestAcceptsOnlyEventsOfItsKinds() {
        val request = JournalFetchPlanner.forDate(date, setOf(Post), SelfOwner, null, clock).single()
        assertTrue(request.accepts(setOf(Post)))
        assertFalse(request.accepts(setOf(Reply)))
        assertFalse(request.accepts(emptySet()))
    }

    @Test
    fun coverageRecordsOnlyCompletedKindsAndMonthsUpToToday() {
        val coverage = JournalCoverage()
            .markLoaded(month.daysOfMonthUntil(today), setOf(Post, Reply))

        assertTrue(coverage.hasLoadedMonth(month, setOf(Post), today))
        assertFalse(coverage.hasLoadedMonth(month, setOf(Repost), today))
        assertFalse(coverage.forgetDate(LocalDate(2026, 9, 2)).hasLoadedMonth(month, setOf(Post), today))
        assertFalse(coverage.hasLoadedMonth(LocalDate(2026, 10, 1), setOf(Post), today))
        assertEquals(setOf(Repost), coverage.missing(date, setOf(Post, Repost)))
        assertEquals(emptyMap(), coverage.forgetMonth(month).byDate)
    }
}
