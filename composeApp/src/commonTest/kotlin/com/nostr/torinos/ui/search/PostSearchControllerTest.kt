package com.nostr.torinos.ui.search

import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PostSearchControllerTest {
    private val now = 1_000L

    private fun TestScope.controller(
        gateway: FakeSearchGateway,
        config: SearchConfig = testSearchConfig(),
        ownerId: String = "srch-test",
    ) = SearchController(
        scope = backgroundScope,
        gateway = gateway,
        config = config,
        nowEpochSeconds = { now },
        ownerId = ownerId,
    )

    /** `backgroundScope` のジョブは `advanceUntilIdle` では進まないため、現在時刻の処理をすべて実行する。 */
    private fun TestScope.settle() = testScheduler.runCurrent()

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private val SearchController.page: PostPageState get() = posts.state.value.page
    private val SearchController.eventIds: List<String> get() = posts.state.value.events.map { it.id }

    @Test
    fun initialPageUsesTheSearchRelayAsAFiniteFetchWithAFixedUpperBound() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway)
        search.search("  nostr  ", SearchTab.Posts)
        settle()

        val session = gateway.pageSessions.single()
        assertEquals(RelayTarget.Explicit(setOf(TEST_SEARCH_RELAY)), session.spec.target)
        assertIs<SubscriptionBehavior.Fetch>(session.spec.behavior)
        assertEquals("nostr", session.filter.search)
        assertEquals(listOf(1), session.filter.kinds)
        assertEquals(now, session.filter.until)
        assertEquals(3, session.filter.limit)
        assertIs<PostPageState.Loading>(search.page)
    }

    @Test
    fun userSearchDoesNotFetchPostsAndTabsAreFetchedOnlyOnFirstSelection() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway)
        search.search("nostr", SearchTab.Users)
        settle()
        assertEquals(listOf("nostr"), gateway.userSearches)
        assertTrue(gateway.sessions.isEmpty())

        gateway.userSearchResult.complete(listOf("pk" to NostrProfile(name = "alice")))
        settle()
        assertEquals(SearchLoadState.Ready, search.state.value.usersLoadState)

        search.selectTab(SearchTab.Posts)
        settle()
        val page = gateway.pageSessions.single()
        page.events(page("a", 3, newest = 900))
        page.complete()
        settle()
        assertTrue(gateway.engagementSessions.isEmpty(), "付随情報は待機時間の前には取得しない")

        search.selectTab(SearchTab.Users)
        search.selectTab(SearchTab.Posts)
        settle()
        assertEquals(1, gateway.pageSessions.size)
        assertEquals(listOf("nostr"), gateway.userSearches)
    }

    @Test
    fun fullPageCompletesAndClosesAndTheNextPageKeepsExistingResults() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway)
        search.search("nostr", SearchTab.Posts)
        settle()
        val first = gateway.pageSessions.single()
        first.events(page("a", 3, newest = 900))
        first.complete()
        settle()

        assertEquals(PostPageState.Complete(PageEnd.More(nextUntil = 898)), search.page)
        assertTrue(first.isClosed)
        assertEquals(listOf("a0", "a1", "a2"), search.eventIds)

        search.loadMore()
        settle()
        val second = gateway.pageSessions.last()
        assertEquals(898, second.filter.until)
        assertIs<PostPageState.Loading>(search.page)
        assertEquals(PageKind.More, (search.page as PostPageState.Loading).request.kind)
        assertEquals(listOf("a0", "a1", "a2"), search.eventIds)
        assertEquals(
            PostResultsBody.Results(PostResultsFooter.Loading),
            postResultsBody(search.page, hasResults = true),
        )
    }

    @Test
    fun shortPageIsExhausted() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway)
        search.search("nostr", SearchTab.Posts)
        settle()
        gateway.pageSessions.single().apply {
            events(page("a", 2, newest = 900))
            complete()
        }
        settle()
        assertEquals(PostPageState.Complete(PageEnd.Exhausted), search.page)
        search.loadMore()
        settle()
        assertEquals(1, gateway.pageSessions.size)
    }

    @Test
    fun stoppingDuringAnAdditionalPageResumesTheSameRequestWithoutSkippingOrDuplicating() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway)
        search.search("nostr", SearchTab.Posts)
        settle()
        gateway.pageSessions.single().apply {
            events(page("a", 3, newest = 900))
            complete()
        }
        settle()
        search.loadMore()
        settle()
        val partial = gateway.pageSessions.last()
        // 部分受信した古い投稿で境界が動くと、再開後にそれより新しい投稿を飛ばしてしまう。
        partial.event(note("old", createdAt = 100))
        settle()

        search.stop()
        settle()
        assertTrue(partial.isClosed)
        val interrupted = assertIs<PostPageState.Interrupted>(search.page)
        assertEquals(898, interrupted.request.until)
        assertEquals(PageKind.More, interrupted.request.kind)
        assertTrue("old" in search.eventIds, "停止時点までの受信は表示に残す")

        search.resume()
        settle()
        val retried = gateway.pageSessions.last()
        assertNotEquals(partial.id, retried.id)
        assertEquals(898, retried.filter.until)
        retried.events(listOf(note("b0", 896), note("b1", 895), note("old", 100)))
        retried.complete()
        settle()

        assertEquals(PostPageState.Complete(PageEnd.More(nextUntil = 100)), search.page)
        assertEquals(listOf("a0", "a1", "a2", "b0", "b1", "old"), search.eventIds)
    }

    @Test
    fun stoppingDuringTheInitialPageResumesWithTheSameUpperBound() = runTest {
        var clock = now
        val gateway = FakeSearchGateway()
        val search = SearchController(backgroundScope, gateway, testSearchConfig(), { clock }, "srch-test")
        search.search("nostr", SearchTab.Posts)
        settle()
        search.stop()
        clock += 500
        search.resume()
        settle()
        assertEquals(2, gateway.pageSessions.size)
        assertEquals(now, gateway.pageSessions.last().filter.until)
    }

    @Test
    fun resumeDoesNothingForACompletedSearch() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway)
        search.search("nostr", SearchTab.Posts)
        settle()
        gateway.pageSessions.single().apply {
            events(page("a", 1, newest = 900))
            complete()
        }
        settle()
        search.stop()
        search.resume()
        settle()
        assertEquals(1, gateway.pageSessions.size)
    }

    @Test
    fun timeoutKeepsPartialResultsAndRetriesTheSameRequest() = runTest {
        for (received in listOf(0, 1, 3)) {
            val gateway = FakeSearchGateway()
            val search = controller(gateway)
            search.search("nostr", SearchTab.Posts)
            settle()
            gateway.pageSessions.single().apply {
                events(page("a", received, newest = 900))
                complete(RelayOutcome.TimedOut)
            }
            settle()

            val incomplete = assertIs<PostPageState.Incomplete>(search.page, "received=$received")
            assertEquals(PageFailure.Timeout, incomplete.failure)
            assertEquals(received, search.eventIds.size)
            assertTrue(gateway.pageSessions.single().isClosed)
            val body = postResultsBody(search.page, hasResults = received > 0)
            if (received == 0) {
                assertIs<PostResultsBody.Failed>(body, "通信失敗を「結果なし」と表示しない")
            } else {
                assertIs<PostResultsFooter.Failed>(assertIs<PostResultsBody.Results>(body).footer)
            }

            search.loadMore()
            settle()
            assertEquals(1, gateway.pageSessions.size, "未完了のページから次ページへ進まない")

            search.retry()
            settle()
            assertEquals(2, gateway.pageSessions.size)
            assertEquals(now, gateway.pageSessions.last().filter.until)
        }
    }

    private fun SearchController.retry() = retryPosts()

    @Test
    fun relayClosedUnavailableAndOpenFailureAreFailuresNotEmptyResults() = runTest {
        val cases = listOf(
            RelayOutcome.Closed("error: search not supported") to PageFailure.Closed("error: search not supported"),
            RelayOutcome.Unavailable("connection refused") to PageFailure.Unavailable("connection refused"),
        )
        for ((outcome, expected) in cases) {
            val gateway = FakeSearchGateway()
            val search = controller(gateway)
            search.search("nostr", SearchTab.Posts)
            settle()
            gateway.pageSessions.single().complete(outcome)
            settle()
            assertEquals(expected, assertIs<PostPageState.Incomplete>(search.page).failure)
            assertTrue(gateway.pageSessions.single().isClosed)
            assertIs<PostResultsBody.Failed>(postResultsBody(search.page, hasResults = false))
        }

        val gateway = FakeSearchGateway()
        gateway.failNextOpen = IllegalStateException("boom")
        val search = controller(gateway)
        search.search("nostr", SearchTab.Posts)
        settle()
        assertIs<PageFailure.Error>(assertIs<PostPageState.Incomplete>(search.page).failure)
    }

    @Test
    fun eventsQueuedBeforeEoseAreAllAppliedBeforeThePageCompletes() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway, testSearchConfig(pageSize = 50))
        search.search("nostr", SearchTab.Posts)
        settle()
        gateway.pageSessions.single().apply {
            events(page("a", 40, newest = 900))
            complete()
        }
        settle()
        assertEquals(PostPageState.Complete(PageEnd.Exhausted), search.page)
        assertEquals(40, search.eventIds.size)
    }

    @Test
    fun newSearchIgnoresLateResultsOfThePreviousSearch() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway)
        search.search("old", SearchTab.Posts)
        settle()
        val old = gateway.pageSessions.single()
        old.event(note("o0", 900))
        settle()

        search.search("new", SearchTab.Posts)
        settle()
        assertTrue(old.isClosed)
        old.event(note("o1", 899))
        old.complete()
        settle()

        assertTrue(search.eventIds.isEmpty())
        assertIs<PostPageState.Loading>(search.page)
        assertEquals("new", gateway.pageSessions.last().filter.search)
    }

    @Test
    fun stalledEngagementBatchOfThePreviousSearchDoesNotTouchTheNewSearch() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway)
        search.search("old", SearchTab.Posts)
        settle()
        gateway.pageSessions.single().apply {
            events(page("a", 1, newest = 900))
            complete()
        }
        settle()
        advance(500)
        val oldBatch = gateway.engagementSessions.single()

        search.search("new", SearchTab.Posts)
        settle()
        assertTrue(oldBatch.isClosed)
        oldBatch.event(reaction("r", target = "a0"))
        settle()
        advance(100)
        assertTrue(search.posts.state.value.engagements.isEmpty())
    }

    @Test
    fun overflowingPageIsClosedAsIncomplete() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway, testSearchConfig(pageSize = 3, pageOverflowBudget = 2))
        search.search("nostr", SearchTab.Posts)
        settle()
        val session = gateway.pageSessions.single()
        session.events(page("a", 6, newest = 900))
        settle()

        assertEquals(PageFailure.Overflow, assertIs<PostPageState.Incomplete>(search.page).failure)
        assertTrue(session.isClosed)
        assertEquals(listOf("a0", "a1", "a2"), search.eventIds, "要求件数を超えた分は表示しない")
    }

    @Test
    fun extraEventsWithinTheBudgetAreIgnoredAndTheCursorComesFromAcceptedEvents() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway, testSearchConfig(pageSize = 3, pageOverflowBudget = 2))
        search.search("nostr", SearchTab.Posts)
        settle()
        gateway.pageSessions.single().apply {
            events(page("a", 5, newest = 900))
            complete()
        }
        settle()
        assertEquals(PostPageState.Complete(PageEnd.More(nextUntil = 898)), search.page)
        assertEquals(listOf("a0", "a1", "a2"), search.eventIds)
    }

    @Test
    fun reachingTheResultLimitStopsPaging() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway, testSearchConfig(pageSize = 3, maxResultEvents = 5))
        search.search("nostr", SearchTab.Posts)
        settle()
        gateway.pageSessions.single().apply {
            events(page("a", 3, newest = 900))
            complete()
        }
        settle()
        search.loadMore()
        settle()
        gateway.pageSessions.last().apply {
            events(page("b", 3, newest = 800))
            complete()
        }
        settle()
        assertEquals(PostPageState.Complete(PageEnd.ResultLimit), search.page)
        assertEquals(5, search.eventIds.size)
    }

    @Test
    fun loadMoreRepeatedlyStartsOnlyOnePageAndStopIsIdempotent() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway)
        search.search("nostr", SearchTab.Posts)
        settle()
        gateway.pageSessions.single().apply {
            events(page("a", 3, newest = 900))
            complete()
        }
        settle()
        repeat(5) { search.loadMore() }
        settle()
        assertEquals(2, gateway.pageSessions.size)

        search.stop()
        search.stop()
        settle()
        assertTrue(gateway.openSessions.isEmpty())
        assertEquals(1, gateway.pageSessions.last().closeCount)
        assertIs<PostPageState.Interrupted>(search.page)
    }

    @Test
    fun twoControllersSearchingAtOnceUseDistinctSubscriptionIds() = runTest {
        val gateway = FakeSearchGateway()
        val first = controller(gateway, ownerId = newSearchOwnerId())
        val second = controller(gateway, ownerId = newSearchOwnerId())
        first.search("nostr", SearchTab.Posts)
        second.search("nostr", SearchTab.Posts)
        settle()
        assertEquals(2, gateway.pageSessions.size)
        assertIs<PostPageState.Loading>(first.page)
        assertIs<PostPageState.Loading>(second.page)

        gateway.pageSessions[0].apply { events(page("a", 1, newest = 900)); complete() }
        settle()
        assertEquals(PostPageState.Complete(PageEnd.Exhausted), first.page)
        assertIs<PostPageState.Loading>(second.page)
    }

    @Test
    fun sameSecondEventsAreOrderedStablyById() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway, testSearchConfig(pageSize = 10))
        search.search("nostr", SearchTab.Posts)
        settle()
        gateway.pageSessions.single().apply {
            events(listOf(note("c", 500), note("a", 500), note("z", 600), note("b", 500)))
            complete()
        }
        settle()
        assertEquals(listOf("z", "a", "b", "c"), search.eventIds)
    }

    @Test
    fun stateUpdatesAreBatchedWhileAPageIsReceived() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway, testSearchConfig(pageSize = 10))
        search.search("nostr", SearchTab.Posts)
        settle()
        val session = gateway.pageSessions.single()
        session.event(note("a", 900))
        settle()
        assertTrue(search.eventIds.isEmpty(), "100ms待ってまとめて反映する")
        session.event(note("b", 899))
        advance(100)
        assertEquals(listOf("a", "b"), search.eventIds)
    }

    @Test
    fun failedUserSearchCanBeRetried() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway)
        search.search("nostr", SearchTab.Users)
        settle()
        gateway.userSearchResult.completeExceptionally(IllegalStateException("x"))
        settle()
        assertEquals(SearchLoadState.Failed, search.state.value.usersLoadState)

        gateway.userSearchResult = kotlinx.coroutines.CompletableDeferred()
        search.retryUsers()
        settle()
        assertEquals(SearchLoadState.Loading, search.state.value.usersLoadState)
        assertEquals(2, gateway.userSearches.size)
    }

    @Test
    fun stoppingAUserSearchResumesItOnReturn() = runTest {
        val gateway = FakeSearchGateway()
        val search = controller(gateway)
        search.search("nostr", SearchTab.Users)
        settle()
        search.stop()
        assertEquals(SearchLoadState.Interrupted, search.state.value.usersLoadState)
        search.resume()
        settle()
        assertEquals(2, gateway.userSearches.size)
        assertNull(gateway.sessions.firstOrNull())
        assertFalse(search.state.value.usersLoadState == SearchLoadState.Interrupted)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchPageBoundaryTest {
    private val config = testSearchConfig(pageSize = 3)
    private fun request(until: Long = 900, limit: Int = 3, retry: Int = 0) =
        PageRequest(generation = 1, serial = 1, kind = PageKind.More, until = until, limit = limit, boundaryRetry = retry)

    @Test
    fun nextPageOverlapsTheBoundarySecond() {
        assertEquals(
            PagePlan.End(PageEnd.More(nextUntil = 850)),
            planNextPage(request(), acceptedCount = 3, oldestCreatedAt = 850, resultCount = 3, config = config),
        )
    }

    @Test
    fun shortOrEmptyPageIsExhausted() {
        assertEquals(PagePlan.End(PageEnd.Exhausted), planNextPage(request(), 2, 850, 2, config))
        assertEquals(PagePlan.End(PageEnd.Exhausted), planNextPage(request(), 0, null, 0, config))
    }

    @Test
    fun fullPageStuckOnTheBoundaryWidensAFiniteNumberOfTimes() {
        assertEquals(
            PagePlan.Widen(request(limit = 6, retry = 1)),
            planNextPage(request(), acceptedCount = 3, oldestCreatedAt = 900, resultCount = 3, config = config),
        )
        assertEquals(
            PagePlan.Widen(request(limit = 12, retry = 2)),
            planNextPage(request(limit = 6, retry = 1), 6, 900, 6, config),
        )
        assertEquals(
            PagePlan.End(PageEnd.BoundaryLimit),
            planNextPage(request(limit = 12, retry = 2), 12, 900, 12, config),
        )
    }

    @Test
    fun controllerWidensAtTheBoundaryAndThenContinuesPastIt() = runTest {
        val gateway = FakeSearchGateway()
        val search = SearchController(backgroundScope, gateway, config, { 1_000L }, "srch-test")
        search.search("nostr", SearchTab.Posts)
        testScheduler.runCurrent()
        val sameSecond = (0 until 5).map { note("x$it", 900) }
        gateway.pageSessions.last().apply { events(sameSecond.take(3)); complete() }
        testScheduler.runCurrent()
        assertEquals(PostPageState.Complete(PageEnd.More(nextUntil = 900)), search.posts.state.value.page)

        search.loadMore()
        testScheduler.runCurrent()
        gateway.pageSessions.last().apply { events(sameSecond.take(3)); complete() }
        testScheduler.runCurrent()
        val widened = gateway.pageSessions.last()
        assertEquals(3, gateway.pageSessions.size, "全件が境界秒なら自動で取り直す")
        assertEquals(900, widened.filter.until)
        assertEquals(6, widened.filter.limit)
        assertIs<PostPageState.Loading>(search.posts.state.value.page)

        widened.events(sameSecond + note("y", 899))
        widened.complete()
        testScheduler.runCurrent()
        assertEquals(PostPageState.Complete(PageEnd.More(nextUntil = 899)), search.posts.state.value.page)
        assertEquals(listOf("x0", "x1", "x2", "x3", "x4", "y"), search.posts.state.value.events.map { it.id })
    }

    @Test
    fun boundaryLimitIsShownAsANoticeNotAsCompletion() {
        assertEquals(
            PostResultsBody.Results(PostResultsFooter.Notice("同じ時刻の投稿が多く、これ以上さかのぼれません")),
            postResultsBody(PostPageState.Complete(PageEnd.BoundaryLimit), hasResults = true),
        )
    }
}
