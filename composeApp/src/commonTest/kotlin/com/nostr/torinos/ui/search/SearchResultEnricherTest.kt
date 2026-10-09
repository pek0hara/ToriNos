package com.nostr.torinos.ui.search

import com.nostr.torinos.model.NostrProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultEnricherTest {
    private fun TestScope.controller(gateway: FakeSearchGateway, config: SearchConfig = testSearchConfig()) =
        SearchController(backgroundScope, gateway, config, { 1_000L }, "srch-test")

    private fun TestScope.settle() = testScheduler.runCurrent()

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun TestScope.completedSearch(
        gateway: FakeSearchGateway,
        events: List<com.nostr.torinos.model.NostrEvent> = page("a", 2, newest = 900),
        config: SearchConfig = testSearchConfig(),
    ): SearchController {
        val search = controller(gateway, config)
        search.search("nostr", SearchTab.Posts)
        settle()
        gateway.pageSessions.single().apply {
            events(events)
            complete()
        }
        settle()
        return search
    }

    private val SearchController.engagements get() = posts.state.value.engagements
    private val SearchController.profiles get() = posts.state.value.profiles

    @Test
    fun profilesAreRequestedOnceAfterAFixedWindowEvenWhileEventsKeepArriving() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway, testSearchConfig(pageSize = 100))
        search.search("nostr", SearchTab.Posts)
        settle()
        val session = gateway.pageSessions.single()
        // 200msごとに届き続けても、最初の追加から500msで依頼する。
        repeat(4) { index ->
            session.event(note("e$index", 900L - index, pubkey = "pk$index"))
            advance(200)
        }
        assertEquals(listOf(setOf("pk0", "pk1", "pk2")), gateway.requestedProfiles)
        advance(500)
        assertEquals(setOf("pk3"), gateway.requestedProfiles.last())
    }

    @Test
    fun mentionedProfilesAreReferencedToo() = runTest {
        val gateway = FakeSearchGateway()
        val mentioned = "a".repeat(64)
        val npub = com.nostr.torinos.crypto.hexToNpub(mentioned)
        completedSearch(gateway, listOf(note("e", 900, pubkey = "author", content = "hi nostr:$npub")))
        advance(500)
        assertEquals(setOf("author", mentioned), gateway.requestedProfiles.single())
    }

    @Test
    fun stoppingDuringTheProfileWindowKeepsTheQueueForResume() = runTest {
        val gateway = FakeSearchGateway()
        val search = completedSearch(gateway)
        advance(200)
        search.stop()
        advance(1_000)
        assertTrue(gateway.requestedProfiles.isEmpty())

        search.resume()
        advance(500)
        assertEquals(listOf(setOf("author-a0", "author-a1")), gateway.requestedProfiles)
    }

    @Test
    fun completedSearchFollowsProfileUpdatesAfterReturningWithoutSearchingAgain() = runTest {
        val gateway = FakeSearchGateway()
        gateway.profileCache["author-a0"] = NostrProfile(name = "before")
        val search = completedSearch(gateway)
        advance(100)
        assertEquals("before", search.profiles["author-a0"]?.name)
        assertEquals(1, gateway.activeProfileWatchers)

        search.stop()
        settle()
        assertEquals(0, gateway.activeProfileWatchers)
        gateway.updateProfile("author-a0", NostrProfile(name = "after"))
        gateway.profileCache["author-a1"] = NostrProfile(name = "new")

        search.resume()
        advance(100)
        assertEquals("after", search.profiles["author-a0"]?.name)
        assertEquals("new", search.profiles["author-a1"]?.name)
        assertEquals(1, gateway.activeProfileWatchers)
        assertEquals(1, gateway.pageSessions.size)

        gateway.updateProfile("author-a1", NostrProfile(name = "live"))
        advance(100)
        assertEquals("live", search.profiles["author-a1"]?.name)
    }

    @Test
    fun engagementIsFetchedOnlyForNewlyAddedPosts() = runTest {
        val gateway = FakeSearchGateway()
        val search = completedSearch(gateway, page("a", 3, newest = 900))
        advance(500)
        val first = gateway.engagementSessions.single()
        assertEquals(listOf("a0", "a1", "a2"), first.filter.eTags)
        first.complete()
        settle()

        search.loadMore()
        settle()
        gateway.pageSessions.last().apply {
            events(page("b", 2, newest = 800))
            complete()
        }
        settle()
        advance(500)
        assertEquals(listOf("b0", "b1"), gateway.engagementSessions.last().filter.eTags)
        assertEquals(2, gateway.engagementSessions.size)
    }

    @Test
    fun engagementCountsRepliesReactionsRepostsAndQuotes() = runTest {
        val gateway = FakeSearchGateway()
        val search = completedSearch(gateway, page("a", 1, newest = 900))
        advance(500)
        gateway.engagementSessions.single().apply {
            event(reaction("r1", "a0", content = "+"))
            event(reaction("r2", "a0", content = "🎉"))
            event(reaction("r1", "a0", content = "+"))
            event(reply("p1", "a0"))
            event(repost("s1", "a0", pubkey = "alice"))
            event(quote("q1", "a0", pubkey = "alice"))
            event(reaction("other", "not-tracked"))
            complete()
        }
        settle()
        advance(100)
        val engagement = search.engagements.getValue("a0")
        assertEquals(2, engagement.reactionCount)
        assertEquals(1, engagement.likeReactionCount)
        assertEquals(1, engagement.replyCount)
        assertEquals(2, engagement.repostCount)
        assertEquals(listOf("alice"), engagement.repostPubkeys)
        assertEquals(listOf("r1", "r2"), engagement.reactionEvents.map { it.id })
        assertEquals(1, engagement.unicodeReactions.size)
    }

    @Test
    fun interruptedEngagementBatchIsRefetchedOnResumeWithoutDoubleCounting() = runTest {
        val gateway = FakeSearchGateway()
        val search = completedSearch(gateway, page("a", 2, newest = 900))
        advance(500)
        val interrupted = gateway.engagementSessions.single()
        interrupted.event(reaction("r1", "a0"))
        settle()

        search.stop()
        settle()
        assertTrue(interrupted.isClosed)
        assertEquals(1, search.engagements.getValue("a0").reactionCount, "停止時に受信済みの集計を反映する")

        search.resume()
        advance(500)
        val retried = gateway.engagementSessions.last()
        assertEquals(listOf("a0", "a1"), retried.filter.eTags)
        retried.event(reaction("r1", "a0"))
        retried.event(reaction("r2", "a0"))
        retried.complete()
        settle()
        advance(100)
        assertEquals(2, search.engagements.getValue("a0").reactionCount)
    }

    @Test
    fun batchEventBudgetEndsTheFetchAndTheTargetsAreNotFetchedAgain() = runTest {
        val gateway = FakeSearchGateway()
        val search = completedSearch(
            gateway,
            page("a", 1, newest = 900),
            testSearchConfig(engagementBatchEventLimit = 2),
        )
        advance(500)
        val session = gateway.engagementSessions.single()
        repeat(4) { session.event(reaction("r$it", "a0")) }
        settle()
        assertTrue(session.isClosed)
        advance(1_000)
        assertEquals(2, search.engagements.getValue("a0").reactionCount)
        assertEquals(1, gateway.engagementSessions.size)
    }

    @Test
    fun accumulatorKeepsCountsOfEvictedTargetsAndNeverRecountsThem() {
        val accumulator = SearchEngagementAccumulator(maxTrackedTargets = 2)
        accumulator.track("t1")
        accumulator.apply(reaction("r1", "t1"))
        accumulator.track("t2")
        accumulator.track("t3")
        assertTrue(!accumulator.isTracked("t1"))

        // 外れた対象への再応答は受け付けない（索引を忘れて再計上しない）。
        accumulator.apply(reaction("r1", "t1"))
        accumulator.apply(reaction("r9", "t1"))
        assertEquals(1, accumulator.snapshot().getValue("t1").reactionCount)

        accumulator.apply(reaction("r2", "t3"))
        accumulator.apply(reaction("r2", "t3"))
        assertEquals(1, accumulator.snapshot().getValue("t3").reactionCount)
    }

    @Test
    fun accumulatorSnapshotIsReusedWhenNothingChanged() {
        val accumulator = SearchEngagementAccumulator(maxTrackedTargets = 2)
        accumulator.track("t1")
        accumulator.apply(reaction("r1", "t1"))
        val first = accumulator.snapshot()
        accumulator.apply(reaction("r1", "t1"))
        assertTrue(first === accumulator.snapshot())
    }

    @Test
    fun reactorsAndRepostersProfilesAreRequested() = runTest {
        val gateway = FakeSearchGateway()
        completedSearch(gateway, page("a", 1, newest = 900))
        advance(500)
        gateway.engagementSessions.single().apply {
            event(reaction("r1", "a0", pubkey = "bob"))
            event(repost("s1", "a0", pubkey = "carol"))
            complete()
        }
        settle()
        advance(500)
        assertEquals(setOf("bob", "carol"), gateway.requestedProfiles.last())
    }

    @Test
    fun stopLeavesNoSearchOwnedSessionOrWatcher() = runTest {
        val gateway = FakeSearchGateway()
        val search = completedSearch(gateway, page("a", 3, newest = 900))
        advance(500)
        search.loadMore()
        settle()
        search.stop()
        settle()
        assertTrue(gateway.openSessions.isEmpty())
        assertEquals(0, gateway.activeProfileWatchers)
    }
}
