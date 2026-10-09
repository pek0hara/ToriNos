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
    private val me = setOf(OWNER)

    /** [OWNER]だけが[kinds]を取っていない。 */
    private fun own(vararg kinds: JournalActivityKind) = mapOf(OWNER to kinds.toSet())

    @Test
    fun authoredKindsGoToTheSelectedRelayWithCommentsInTheirOwnFilter() {
        val requests = JournalFetchPlanner.forDate(date, own(Post, Reply, Repost), "wss://memo", clock)

        val authored = requests.single()
        assertEquals(RelayTarget.Single("wss://memo"), authored.target)
        assertEquals(setOf(Post, Reply, Repost), authored.kinds)
        assertEquals(me, authored.members)
        assertEquals(listOf(date), authored.dates)
        val (notes, comments) = authored.filters
        assertEquals(listOf(1, 6, 16), notes.kinds)
        assertEquals(listOf(OWNER), notes.authors)
        assertEquals(clock.startOfDay(date), notes.since)
        assertEquals(clock.endOfDay(date), notes.until)
        assertEquals(listOf(COMMENT_EVENT_KIND), comments.kinds)
        assertEquals(listOf("1"), comments.rootKindTags)
    }

    @Test
    fun receivedLikesAreFetchedFromAllRelaysSeparately() {
        val requests = JournalFetchPlanner.forDate(date, own(Post, ReceivedLike), "wss://memo", clock)

        assertEquals(2, requests.size)
        val received = requests.single { ReceivedLike in it.kinds }
        assertEquals(RelayTarget.AllEnabled, received.target)
        assertEquals(listOf(7), received.filters.single().kinds)
        assertEquals(listOf(OWNER), received.filters.single().pTags)
        assertEquals(setOf(Post), requests.single { it !== received }.kinds)
    }

    @Test
    fun withoutASelectedRelayAuthoredKindsUseAllRelays() {
        val request = JournalFetchPlanner.forDate(date, own(Post), null, clock).single()
        assertEquals(RelayTarget.AllEnabled, request.target)
    }

    @Test
    fun membersMissingTheSameKindsShareOneRequest() {
        val filter = JournalFetchPlanner.forDate(date, mapOf(OWNER to setOf(Like), OTHER to setOf(Like)), null, clock)
            .single().filters.single()
        assertEquals(listOf(7), filter.kinds)
        assertEquals(listOf(OTHER, OWNER).sorted(), filter.authors)
    }

    @Test
    fun membersMissingDifferentKindsGetSeparateRequests() {
        // 自分は投稿だけ取っていない。新しく表示した OTHER は投稿も返信も取っていない。
        val requests = JournalFetchPlanner.forDate(
            date,
            mapOf(OWNER to setOf(Post), OTHER to setOf(Post, Reply)),
            "wss://memo",
            clock,
        )

        assertEquals(2, requests.size)
        assertEquals(setOf(OWNER), requests.single { it.kinds == setOf(Post) }.members)
        assertEquals(setOf(OTHER), requests.single { it.kinds == setOf(Post, Reply) }.members)
        assertEquals(listOf(OTHER), requests.single { it.kinds == setOf(Post, Reply) }.filters.first().authors)
    }

    @Test
    fun receivedLikesAreFetchedOnceForEveryMemberMissingThem() {
        val requests = JournalFetchPlanner.forDate(
            date,
            mapOf(OWNER to setOf(ReceivedLike), OTHER to setOf(Post, ReceivedLike)),
            "wss://memo",
            clock,
        )
        val received = requests.single { ReceivedLike in it.kinds }
        assertEquals(listOf(OTHER, OWNER).sorted(), received.filters.single().pTags)
        assertEquals(setOf(OWNER, OTHER), received.members)
        assertEquals(RelayTarget.AllEnabled, received.target)
        assertEquals(setOf(OTHER), requests.single { it !== received }.members)
    }

    @Test
    fun nothingToFetchWhenNoOneMissesAnything() {
        assertTrue(JournalFetchPlanner.forDate(date, emptyMap(), null, clock).isEmpty())
    }

    @Test
    fun monthReceivedLikesCoverOnlyMissingDatesUpToToday() {
        val coverage = JournalCoverage().markLoaded(listOf(LocalDate(2026, 9, 1)), setOf(ReceivedLike), me)

        val request = JournalFetchPlanner.monthReceivedLikes(month, today, setOf(ReceivedLike), coverage, me, clock)!!

        assertEquals(14, request.dates.size)
        assertEquals(LocalDate(2026, 9, 2), request.dates.first())
        assertEquals(today, request.dates.last())
        assertEquals(me, request.members)
        assertEquals(clock.startOfDay(month), request.filters.single().since)
        assertEquals(clock.endOfDay(today), request.filters.single().until)
        assertEquals(JournalFetchPlanner.MONTH_RECEIVED_LIKE_LIMIT, request.filters.single().limit)
    }

    @Test
    fun monthReceivedLikesOnlyAskForMembersThatHaveNotLoadedThem() {
        val coverage = JournalCoverage().markLoaded(month.daysOfMonthUntil(today), setOf(ReceivedLike), me)

        val request = JournalFetchPlanner.monthReceivedLikes(
            month, today, setOf(ReceivedLike), coverage, setOf(OWNER, OTHER), clock,
        )!!

        assertEquals(setOf(OTHER), request.members)
        assertEquals(listOf(OTHER), request.filters.single().pTags)
    }

    @Test
    fun monthReceivedLikesAreSkippedWhenLoadedOrNotSelected() {
        val loaded = JournalCoverage().markLoaded(month.daysOfMonthUntil(today), setOf(ReceivedLike), me)
        assertNull(JournalFetchPlanner.monthReceivedLikes(month, today, setOf(ReceivedLike), loaded, me, clock))
        assertNull(JournalFetchPlanner.monthReceivedLikes(month, today, setOf(Post), JournalCoverage(), me, clock))
        assertFalse(JournalFetchPlanner.willFetchMonthReceivedLikes(month, today, setOf(ReceivedLike), loaded, me))
        assertTrue(JournalFetchPlanner.willFetchMonthReceivedLikes(month, today, setOf(ReceivedLike), JournalCoverage(), me))
    }

    @Test
    fun requestAcceptsOnlyEventsOfItsKindsForItsMembers() {
        val request = JournalFetchPlanner.forDate(date, own(Post), null, clock).single()
        assertTrue(request.accepts(mapOf(OWNER to setOf(Post))))
        assertFalse(request.accepts(mapOf(OWNER to setOf(Reply))))
        // ほかのメンバーの投稿は、このリクエストでは採らない。
        assertFalse(request.accepts(mapOf(OTHER to setOf(Post))))
        assertFalse(request.accepts(emptyMap()))
    }

    @Test
    fun coverageRecordsOnlyCompletedKindsAndMonthsUpToToday() {
        val coverage = JournalCoverage()
            .markLoaded(month.daysOfMonthUntil(today), setOf(Post, Reply), me)

        assertTrue(coverage.hasLoadedMonth(month, setOf(Post), today, me))
        assertFalse(coverage.hasLoadedMonth(month, setOf(Repost), today, me))
        assertFalse(coverage.forgetDate(LocalDate(2026, 9, 2)).hasLoadedMonth(month, setOf(Post), today, me))
        assertFalse(coverage.hasLoadedMonth(LocalDate(2026, 10, 1), setOf(Post), today, me))
        assertEquals(setOf(Repost), coverage.missing(date, setOf(Post, Repost), OWNER))
        assertEquals(0, coverage.forgetMonth(month).coveredDays)
    }

    @Test
    fun coverageIsTrackedPerMember() {
        val coverage = JournalCoverage().markLoaded(month.daysOfMonthUntil(today), setOf(Post), me)

        assertFalse(coverage.hasLoadedMonth(month, setOf(Post), today, setOf(OWNER, OTHER)))
        assertEquals(mapOf(OTHER to setOf(Post)), coverage.missingByMember(date, setOf(Post), setOf(OWNER, OTHER)))
        assertEquals(emptySet(), coverage.loadedKinds(date, setOf(Post), setOf(OWNER, OTHER)))
        assertEquals(setOf(Post), coverage.loadedKinds(date, setOf(Post), me))
        assertFalse(coverage.forgetMembers(listOf(OWNER)).hasLoadedMonth(month, setOf(Post), today, me))
    }
}
