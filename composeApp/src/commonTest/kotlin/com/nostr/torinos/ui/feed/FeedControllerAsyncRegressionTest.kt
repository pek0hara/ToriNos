package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.RetryDisposition
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedControllerAsyncRegressionTest {
    @Test
    fun longBackgroundResetClearsTimelineAndStartsFromLatestOnlyOnce() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()

        gateway.liveSessions.single().event(event("old", 10))
        advanceTimeBy(151)
        runCurrent()
        assertEquals(listOf("old"), controller.state.value.events.map { it.id })

        assertTrue(controller.resetToLatest(request = 1))
        runCurrent()
        assertTrue(controller.state.value.events.isEmpty())
        assertTrue(gateway.sessions.all { it.closed })
        assertFalse(controller.resetToLatest(request = 1))

        val previousFetchCount = gateway.fetchSessions.size
        controller.startSubscriptions()
        runCurrent()
        assertEquals(previousFetchCount + 1, gateway.fetchSessions.size)
        controller.close()
    }

    @Test
    fun longBackgroundResetDiscardsPendingTimelineBatch() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()

        gateway.liveSessions.single().event(event("pending", 10))
        runCurrent()
        assertTrue(controller.resetToLatest(request = 1))
        controller.startSubscriptions()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(controller.state.value.events.isEmpty())
        controller.close()
    }

    @Test
    fun liveEventBurstIsPublishedAsOneDelayedBatch() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val live = gateway.liveSessions.single()
        val observedEventStates = mutableListOf<List<String>>()
        val observer = backgroundScope.launch {
            controller.state
                .map { state -> state.events.map { it.id } }
                .distinctUntilChanged()
                .drop(1)
                .collect(observedEventStates::add)
        }
        runCurrent()

        live.event(event("old", 10))
        live.event(event("new", 30))
        live.event(event("middle", 20))
        runCurrent()

        assertTrue(controller.state.value.events.isEmpty())
        advanceTimeBy(149)
        runCurrent()
        assertTrue(controller.state.value.events.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(
            listOf("new", "middle", "old"),
            controller.state.value.events.map { it.id },
        )
        assertEquals(listOf(listOf("new", "middle", "old")), observedEventStates)
        observer.cancel()
        controller.close()
    }

    @Test
    fun pendingTimelineWorkStaysDormantUntilSubscriptionsRestart() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()

        gateway.liveSessions.single().event(event("pending", 10))
        runCurrent()
        controller.stopSubscriptions()
        runCurrent()

        assertTrue(controller.state.value.events.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(controller.state.value.events.isEmpty())

        controller.startSubscriptions()
        runCurrent()
        assertTrue(controller.state.value.events.isEmpty())
        advanceTimeBy(149)
        runCurrent()
        assertTrue(controller.state.value.events.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("pending"), controller.state.value.events.map { it.id })
        controller.close()
    }

    @Test
    fun closingControllerCancelsPendingTimelineWork() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()

        gateway.liveSessions.single().event(event("pending", 10))
        runCurrent()
        controller.close()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(controller.state.value.events.isEmpty())
    }

    @Test
    fun closedControllerCannotRestartSubscriptions() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val sessionCountBeforeClose = gateway.sessions.size

        controller.close()
        controller.startSubscriptions()
        runCurrent()

        assertEquals(sessionCountBeforeClose, gateway.sessions.size)
        assertFalse(controller.resetToLatest(request = 1))
    }

    @Test
    fun historyCompletionFlushesPendingEventsWithoutWaitingForBatchDelay() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val history = gateway.fetchSessions.single()

        history.event(event("history", 10))
        runCurrent()
        assertTrue(controller.state.value.events.isEmpty())

        history.complete()
        runCurrent()
        assertEquals(listOf("history"), controller.state.value.events.map { it.id })
        controller.close()
    }

    @Test
    fun responsiveRelayHidesIndicatorWhileSlowRelayContinuesLoading() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-up", "relay-down"))
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val firstUp = gateway.fetchSessions.single { it.target == RelayTarget.Single("relay-up") }
        val firstDown = gateway.fetchSessions.single { it.target == RelayTarget.Single("relay-down") }
        repeat(30) { index ->
            val item = event("initial-$index", 100L - index)
            firstUp.event(item, relay = "relay-up")
            firstDown.event(item, relay = "relay-down")
        }
        firstUp.complete("relay-up")
        firstDown.complete("relay-down")
        runCurrent()

        controller.loadMore()
        runCurrent()
        val responsive = gateway.fetchSessions.last { it.target == RelayTarget.Single("relay-up") }
        val slow = gateway.fetchSessions.last { it.target == RelayTarget.Single("relay-down") }

        responsive.event(event("from-responsive-relay", 70), relay = "relay-up")
        responsive.eose(relay = "relay-up")
        runCurrent()
        assertTrue(controller.state.value.isLoadingMore)
        assertEquals(30, controller.state.value.events.size)

        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(controller.state.value.isLoadingMore)

        advanceTimeBy(1)
        runCurrent()
        assertFalse(controller.state.value.isLoadingMore)
        assertFalse(slow.closed)
        assertEquals(31, controller.state.value.events.size)
        assertTrue(controller.state.value.events.any { it.id == "from-responsive-relay" })

        responsive.complete("relay-up")
        slow.event(event("from-slow-relay", 69), relay = "relay-down")
        slow.complete("relay-down")
        runCurrent()
        assertTrue(controller.state.value.events.any { it.id == "from-slow-relay" })
        controller.close()
    }

    @Test
    fun initialEmptyStateWaitsForSlowRelayAndLateEventBecomesContent() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-up", "relay-down"))
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val responsive = gateway.fetchSessions.single { it.target == RelayTarget.Single("relay-up") }
        val slow = gateway.fetchSessions.single { it.target == RelayTarget.Single("relay-down") }

        responsive.complete("relay-up")
        runCurrent()
        assertEquals(FeedViewModel.InitialFeedState.Loading, controller.state.value.initialFeedState)

        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(FeedViewModel.InitialFeedState.Slow, controller.state.value.initialFeedState)

        slow.event(event("late", 10), relay = "relay-down")
        slow.complete("relay-down")
        runCurrent()
        assertEquals(FeedViewModel.InitialFeedState.ContentReady, controller.state.value.initialFeedState)
        assertEquals(listOf("late"), controller.state.value.events.map { it.id })
        controller.close()
    }

    @Test
    fun initialEmptyStateAppearsOnlyAfterEveryRelayCompletesSuccessfully() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-a", "relay-b"))
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val relayA = gateway.fetchSessions.single { it.target == RelayTarget.Single("relay-a") }
        val relayB = gateway.fetchSessions.single { it.target == RelayTarget.Single("relay-b") }

        relayA.complete("relay-a")
        runCurrent()
        assertEquals(FeedViewModel.InitialFeedState.Loading, controller.state.value.initialFeedState)

        relayB.complete("relay-b")
        runCurrent()
        assertEquals(FeedViewModel.InitialFeedState.Empty, controller.state.value.initialFeedState)
        controller.close()
    }

    @Test
    fun timedOutRelayDoesNotPreventResponsiveRelayFromAdvancing() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-up", "relay-down"))
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val responsive = gateway.fetchSessions.single { it.target == RelayTarget.Single("relay-up") }
        val stalled = gateway.fetchSessions.single { it.target == RelayTarget.Single("relay-down") }

        repeat(30) { index ->
            responsive.event(event("from-up-$index", 100L - index), relay = "relay-up")
        }
        responsive.complete("relay-up")
        stalled.complete("relay-down", timedOut = true)
        runCurrent()

        assertTrue(controller.state.value.canLoadMore)
        controller.loadMore()
        runCurrent()

        val nextResponsive = gateway.fetchSessions.last()
        assertEquals(RelayTarget.Single("relay-up"), nextResponsive.target)
        assertTrue(nextResponsive.filters.all { it.until == 71L })
        nextResponsive.event(event("older-from-up", 70), relay = "relay-up")
        nextResponsive.complete("relay-up")
        runCurrent()

        assertTrue(controller.state.value.events.any { it.id == "older-from-up" })
        controller.close()
    }

    @Test
    fun multiRelayThirtyEventBoundaryLoadsNextPageWithInclusiveCursor() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-a", "relay-b"))
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val firstA = gateway.fetchSessions.single { it.target == RelayTarget.Single("relay-a") }
        val firstB = gateway.fetchSessions.single { it.target == RelayTarget.Single("relay-b") }

        (71L..100L).forEach { createdAt ->
            val item = event("event-$createdAt", createdAt)
            firstA.event(item, relay = "relay-a")
            firstB.event(item, relay = "relay-b")
        }
        firstA.complete("relay-a")
        firstB.complete("relay-b")
        runCurrent()

        assertEquals(30, controller.state.value.events.size)
        assertTrue(controller.state.value.canLoadMore)
        assertFalse(firstA.deduplicateEvents)

        controller.loadMore()
        runCurrent()
        val secondPage = gateway.fetchSessions.last { it.target == RelayTarget.Single("relay-a") }
        assertTrue(secondPage.filters.all { it.until == 71L })

        (42L..71L).forEach { createdAt ->
            secondPage.event(event("event-$createdAt", createdAt), relay = "relay-a")
        }
        secondPage.complete("relay-a")
        runCurrent()

        assertEquals(59, controller.state.value.events.size)
        controller.close()
    }

    @Test
    fun sameSecondBoundaryExpandsLimitBeforeAdvancingCursor() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val firstPage = gateway.fetchSessions.single()
        repeat(30) { index ->
            firstPage.event(event("same-second-$index", 100))
        }
        firstPage.complete()
        runCurrent()

        controller.loadMore()
        runCurrent()
        val boundaryRetry = gateway.fetchSessions.last()
        assertTrue(boundaryRetry.filters.all { it.until == 100L && it.limit == 30 })
        repeat(30) { index ->
            boundaryRetry.event(event("same-second-$index", 100))
        }
        boundaryRetry.complete()
        runCurrent()

        controller.loadMore()
        runCurrent()
        val expandedPage = gateway.fetchSessions.last()
        assertEquals(3, gateway.fetchSessions.size)
        assertTrue(expandedPage.filters.all { it.until == 100L && it.limit == 60 })
        repeat(60) { index ->
            expandedPage.event(event("same-second-$index", 100))
        }
        expandedPage.complete()
        runCurrent()

        assertEquals(60, controller.state.value.events.size)
        controller.loadMore()
        runCurrent()
        assertTrue(gateway.fetchSessions.last().filters.all { it.until == 100L && it.limit == 120 })
        controller.close()
    }

    @Test
    fun relayRemovedAfterPageStartsDoesNotRemainPendingForever() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-up", "relay-removed"))
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val firstPage = gateway.fetchSessions.single { it.target == RelayTarget.Single("relay-up") }

        gateway.relayUrls.value = setOf("relay-up")
        runCurrent()
        firstPage.event(event("from-up", 100), relay = "relay-up")
        firstPage.complete("relay-up")
        runCurrent()

        assertFalse(controller.state.value.canLoadMore)
        controller.loadMore()
        runCurrent()
        assertEquals(2, gateway.fetchSessions.size)
        controller.close()
    }

    @Test
    fun relayAddedLaterStartsFromTheOriginalHistoryFloor() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-a"))
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val firstPage = gateway.fetchSessions.single()
        val historyFloor = firstPage.filters.single().until
        firstPage.event(event("from-a", 100), relay = "relay-a")
        firstPage.complete("relay-a")
        runCurrent()

        gateway.relayUrls.value = setOf("relay-a", "relay-b")
        runCurrent()

        val addedRelayPage = gateway.fetchSessions.single {
            it.target == RelayTarget.Single("relay-b")
        }
        assertTrue(addedRelayPage.filters.all { it.until == historyFloor })
        controller.close()
    }

    @Test
    fun defaultFeedRequestsOnlyKind1() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()

        assertTrue(gateway.sessions.isNotEmpty())
        gateway.sessions.forEach { session ->
            assertEquals(listOf(1), session.filters.single().kinds)
        }
        controller.close()
    }

    @Test
    fun historyUsesFixedFloorAndAdvancesEachLogicalFilterIndependently() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(
            includeRepliesInFeed = true,
            scope = backgroundScope,
            subscriptions = gateway,
            feedEventKinds = setOf(1, COMMENT_EVENT_KIND),
        )
        runCurrent()
        val firstPage = gateway.fetchSessions.single()
        val historyFloor = firstPage.filters.map { it.until }.distinct().single()
        assertTrue(historyFloor != null)
        assertTrue(gateway.liveSessions.single().filters.all { it.since == historyFloor })

        repeat(30) { index ->
            firstPage.event(event("post-$index", 100L - index), relay = "relay")
        }
        firstPage.event(
            event(
                id = "one-comment",
                createdAt = 90,
                kind = COMMENT_EVENT_KIND,
                tags = supportedCommentTags(),
            ),
            relay = "relay",
        )
        firstPage.complete("relay")
        runCurrent()

        controller.loadMore()
        runCurrent()
        val nextPage = gateway.fetchSessions.last()
        assertEquals(1, nextPage.filters.size)
        assertEquals(listOf(1), nextPage.filters.single().kinds)
        assertEquals(71L, nextPage.filters.single().until)
        controller.close()
    }

    @Test
    fun structuralRelayRefusalIsolatesTheRejectedLogicalFilter() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(
            includeRepliesInFeed = true,
            scope = backgroundScope,
            subscriptions = gateway,
            feedEventKinds = setOf(1, COMMENT_EVENT_KIND),
        )
        runCurrent()
        val combined = gateway.fetchSessions.single()
        combined.closed("relay", RetryDisposition.RetryOnFilterChange)
        combined.completeWith("relay" to RelayOutcome.Closed("unsupported filter"))
        runCurrent()

        advanceTimeBy(2_000)
        runCurrent()
        val firstProbe = gateway.fetchSessions.last()
        assertEquals(1, firstProbe.filters.size)
        firstProbe.closed("relay", RetryDisposition.RetryOnFilterChange)
        firstProbe.completeWith("relay" to RelayOutcome.Closed("unsupported filter"))
        runCurrent()

        val secondProbe = gateway.fetchSessions.last()
        assertEquals(3, gateway.fetchSessions.size)
        assertEquals(1, secondProbe.filters.size)
        assertTrue(secondProbe.filters.single().kinds != firstProbe.filters.single().kinds)
        controller.close()
    }

    @Test
    fun profilePostsAndRepliesIncludesKind1111Comments() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(
            includeRepliesInFeed = true,
            scope = backgroundScope,
            subscriptions = gateway,
            feedEventKinds = setOf(1, COMMENT_EVENT_KIND),
        )
        runCurrent()

        gateway.sessions.forEach { session ->
            assertEquals(listOf(1), session.filters[0].kinds)
            assertEquals(listOf(COMMENT_EVENT_KIND), session.filters[1].kinds)
            assertEquals(listOf("1"), session.filters[1].rootKindTags)
        }
        gateway.liveSessions.single().event(
            event(
                id = "comment",
                createdAt = 10,
                kind = COMMENT_EVENT_KIND,
                tags = supportedCommentTags(),
            ),
        )
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertEquals(listOf("comment"), controller.state.value.events.map { it.id })
        controller.close()
    }

    @Test
    fun profileRejectsUnsupportedAndMalformedKind1111Comments() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(
            includeRepliesInFeed = true,
            scope = backgroundScope,
            subscriptions = gateway,
            feedEventKinds = setOf(1, COMMENT_EVENT_KIND),
        )
        runCurrent()
        val live = gateway.liveSessions.single()

        live.event(event("article-comment", 20, COMMENT_EVENT_KIND, supportedCommentTags(rootKind = 30023)))
        live.event(event("malformed-comment", 10, COMMENT_EVENT_KIND, listOf(listOf("K", "1"))))
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertTrue(controller.state.value.events.isEmpty())
        controller.close()
    }

    @Test
    fun kind1111ReplyFetchesKind1111ParentForReplyCard() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(
            includeRepliesInFeed = true,
            scope = backgroundScope,
            subscriptions = gateway,
            feedEventKinds = setOf(1, COMMENT_EVENT_KIND),
        )
        runCurrent()

        gateway.liveSessions.single().event(
            event(
                id = "child-comment",
                createdAt = 10,
                kind = COMMENT_EVENT_KIND,
                tags = listOf(
                    listOf("E", "root", "", "root-author"),
                    listOf("K", "1"),
                    listOf("P", "root-author"),
                    listOf("e", "parent-comment", "", "parent-author"),
                    listOf("k", COMMENT_EVENT_KIND.toString()),
                    listOf("p", "parent-author"),
                ),
            ),
        )
        runCurrent()

        val replyParentRequest = gateway.subscribedFilters.single {
            it.ids?.contains("parent-comment") == true
        }
        assertEquals(listOf(1, COMMENT_EVENT_KIND), replyParentRequest.kinds)
        controller.close()
    }

    @Test
    fun delayedHistoryFromSubscriptionBeforeRefreshCannotOverwriteNewState() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val historyA = gateway.fetchSessions.single()

        controller.refresh()
        runCurrent()
        val historyB = gateway.fetchSessions.last()
        assertTrue(historyA.closed)

        historyB.event(event("new", 20), relay = "relay")
        historyB.complete("relay")
        runCurrent()
        historyA.event(event("stale", 30), relay = "relay-a")
        historyA.complete("relay-a")
        runCurrent()

        assertEquals(listOf("new"), controller.state.value.events.map { it.id })
        controller.close()
    }

    @Test
    fun loadMoreAndLiveDeliveryOfTheSameEventProduceOneTimelineEntry() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val firstPage = gateway.fetchSessions.single()
        repeat(30) { index -> firstPage.event(event("initial-$index", 100L - index)) }
        firstPage.complete()
        runCurrent()

        controller.loadMore()
        runCurrent()
        val nextPage = gateway.fetchSessions.last()
        val duplicate = event("shared", 60)
        gateway.liveSessions.single().event(duplicate, relay = "live-relay")
        nextPage.event(duplicate, relay = "relay")
        nextPage.complete("relay")
        runCurrent()

        assertEquals(1, controller.state.value.events.count { it.id == duplicate.id })
        controller.close()
    }

    @Test
    fun sameNostrEventFromMultipleRelaysIsAppliedOnce() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val live = gateway.liveSessions.single()
        val duplicate = event("same-event", 10)

        live.event(duplicate, relay = "relay-one")
        live.event(duplicate, relay = "relay-two")
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertEquals(listOf("same-event"), controller.state.value.events.map { it.id })
        controller.close()
    }

    @Test
    fun eventArrivingAfterCloseCannotChangeState() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val live = gateway.liveSessions.single()
        live.event(event("before-close", 10))
        runCurrent()
        advanceTimeBy(151)
        runCurrent()
        controller.close()
        runCurrent()
        val afterClose = controller.state.value
        live.event(event("after-close", 20))
        runCurrent()

        assertEquals(afterClose, controller.state.value)
        assertEquals(listOf("before-close"), controller.state.value.events.map { it.id })
    }

    @Test
    fun engagementHistoryFetchOnlyContainsNewlyWatchedIds() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val feedLive = gateway.liveSessions.single()

        feedLive.event(event("first", 10))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        val firstHistory = gateway.engagementFetchSessions.single()
        assertTrue(firstHistory.filters.all { it.since == null && it.until != null })
        assertTrue(firstHistory.filters.all { it.targetIds() == listOf("first") })
        firstHistory.complete()
        runCurrent()

        feedLive.event(event("second", 20))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        val secondHistory = gateway.engagementFetchSessions.last()
        assertEquals(2, gateway.engagementFetchSessions.size)
        assertTrue(secondHistory.filters.all { it.since == null && it.until != null })
        assertTrue(secondHistory.filters.all { it.targetIds() == listOf("second") })

        val engagementLive = gateway.engagementLiveSessions.single()
        assertTrue(engagementLive.filters.all { it.since != null && it.until == null })
        assertTrue(engagementLive.filters.all { it.targetIds() == listOf("first", "second") })
        controller.close()
    }

    @Test
    fun overlappingHistoryAndLiveEngagementIsAppliedOnce() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.liveSessions.single().event(event("note", 10))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        val reaction = event(
            id = "reaction",
            createdAt = 20,
            kind = 7,
            tags = listOf(listOf("e", "note")),
        )
        gateway.engagementFetchSessions.single().event(reaction, relay = "history-relay")
        gateway.engagementLiveSessions.single().event(reaction, relay = "live-relay")
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertEquals(1, controller.state.value.reactionCounts["note"])
        controller.close()
    }

    @Test
    fun historyAndLiveOverlapStaysDeduplicatedBeyondGlobalCacheCapacity() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.liveSessions.single().event(event("note", 10))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        val live = gateway.engagementLiveSessions.single()
        val history = gateway.engagementFetchSessions.single()
        val duplicate = event(
            id = "quote-duplicate",
            createdAt = 20,
            tags = listOf(listOf("q", "note")),
        )
        live.event(duplicate)
        repeat(2_000) { index ->
            live.event(
                event(
                    id = "quote-$index",
                    createdAt = 21L + index,
                    tags = listOf(listOf("q", "note")),
                ),
            )
        }
        history.event(duplicate)
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertEquals(2_001, controller.state.value.repostCounts["note"])
        controller.close()
    }

    @Test
    fun newlyEnabledRelayFetchesHistoryOnlyFromThatRelay() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-a"))
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.liveSessions.single().event(event("note", 10))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        gateway.engagementFetchSessions.single().complete(relay = "relay-a")
        runCurrent()

        gateway.relayUrls.value = setOf("relay-a", "relay-b")
        runCurrent()

        assertEquals(2, gateway.engagementFetchSessions.size)
        val addedRelayFetch = gateway.engagementFetchSessions.last()
        assertEquals(RelayTarget.Explicit(setOf("relay-b")), addedRelayFetch.target)
        assertTrue(addedRelayFetch.filters.all { it.targetIds() == listOf("note") })
        controller.close()
    }

    @Test
    fun reenabledRelayFetchesHistoryAgainAfterBeingDisabled() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-a", "relay-b"))
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.liveSessions.single().event(event("note", 10))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        gateway.engagementFetchSessions.single().completeWith(
            "relay-a" to RelayOutcome.Eose,
            "relay-b" to RelayOutcome.Eose,
        )
        runCurrent()

        gateway.relayUrls.value = setOf("relay-a")
        runCurrent()
        gateway.relayUrls.value = setOf("relay-a", "relay-b")
        runCurrent()

        assertEquals(2, gateway.engagementFetchSessions.size)
        assertEquals(
            RelayTarget.Explicit(setOf("relay-b")),
            gateway.engagementFetchSessions.last().target,
        )
        controller.close()
    }

    @Test
    fun completedEngagementHistoryIsNotFetchedAgainOnResume() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.liveSessions.single().event(event("note", 10))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        gateway.engagementFetchSessions.single().complete()
        runCurrent()

        controller.stopSubscriptions()
        runCurrent()
        controller.startSubscriptions()
        runCurrent()

        assertEquals(1, gateway.engagementFetchSessions.size)
        assertEquals(2, gateway.engagementLiveSessions.size)
        assertTrue(gateway.engagementLiveSessions.last().filters.all { it.since != null })
        controller.close()
    }

    @Test
    fun timedOutEngagementHistoryIsRetriedOnResume() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.liveSessions.single().event(event("note", 10))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        val timedOutHistory = gateway.engagementFetchSessions.single()
        timedOutHistory.complete(timedOut = true)
        runCurrent()

        controller.stopSubscriptions()
        runCurrent()
        controller.startSubscriptions()
        runCurrent()

        assertTrue(timedOutHistory.closed)
        assertEquals(2, gateway.engagementFetchSessions.size)
        assertTrue(gateway.engagementFetchSessions.last().filters.all { it.targetIds() == listOf("note") })
        controller.close()
    }

    private class FakeGateway(initialRelayUrls: Set<String> = setOf("relay")) : FeedSubscriptionGateway {
        val relayUrls = MutableStateFlow(initialRelayUrls)
        val sessions = mutableListOf<FakeSession>()
        val subscribedFilters = mutableListOf<NostrFilter>()
        val fetchSessions: List<FakeSession>
            get() = sessions.filter { it.behavior is SubscriptionBehavior.Fetch }
        val liveSessions: List<FakeSession>
            get() = sessions.filter { it.behavior is SubscriptionBehavior.Live }
        val engagementFetchSessions: List<FakeSession>
            get() = fetchSessions.filter { "-history-" in it.id && it.id.startsWith("reac-") }
        val engagementLiveSessions: List<FakeSession>
            get() = liveSessions.filter { it.id.startsWith("reac-") }

        override val readableRelayUrls: Flow<Set<String>> = relayUrls

        override fun events(subscriptionId: String): Flow<NostrEvent> = emptyFlow()

        override suspend fun targetRelayUrls(target: RelayTarget): Set<String> = when (target) {
            RelayTarget.AllEnabled -> relayUrls.value
            is RelayTarget.Single -> setOf(target.url).intersect(relayUrls.value)
            is RelayTarget.Explicit -> target.urls.intersect(relayUrls.value)
        }

        override suspend fun open(spec: SubscriptionSpec): SubscriptionSession =
            FakeSession(
                spec.id,
                spec.behavior,
                spec.filters,
                spec.target,
                spec.deduplicateEvents,
            ).also(sessions::add)

        override suspend fun subscribe(
            subscriptionId: String,
            filter: NostrFilter,
            target: RelayTarget,
        ): Set<String> {
            subscribedFilters += filter
            return emptySet()
        }

        override fun close(subscriptionId: String) = Unit
    }

    private class FakeSession(
        override val id: String,
        val behavior: SubscriptionBehavior,
        filters: List<NostrFilter>,
        target: RelayTarget,
        val deduplicateEvents: Boolean,
    ) : SubscriptionSession {
        private val mutableSignals = MutableSharedFlow<SubscriptionSignal>(extraBufferCapacity = 4_096)
        override val signals: Flow<SubscriptionSignal> = mutableSignals
        var filters: List<NostrFilter> = filters
            private set
        var target: RelayTarget = target
            private set
        val filterUpdates = mutableListOf<List<NostrFilter>>()
        var closed = false

        fun event(event: NostrEvent, relay: String = "relay") {
            assertTrue(mutableSignals.tryEmit(SubscriptionSignal.Event(relay, event, isLive = false)))
        }

        fun eose(relay: String = "relay") {
            assertTrue(mutableSignals.tryEmit(SubscriptionSignal.Eose(relay)))
        }

        fun closed(relay: String, retry: RetryDisposition) {
            assertTrue(
                mutableSignals.tryEmit(
                    SubscriptionSignal.Closed(relay, "closed", retry),
                ),
            )
        }

        fun complete(relay: String = "relay", timedOut: Boolean = false) {
            assertTrue(
                mutableSignals.tryEmit(
                    SubscriptionSignal.FetchCompleted(
                        outcomes = mapOf(
                            relay to if (timedOut) RelayOutcome.TimedOut else RelayOutcome.Eose,
                        ),
                        timedOut = timedOut,
                    ),
                ),
            )
        }

        fun completeWith(vararg outcomes: Pair<String, RelayOutcome>) {
            assertTrue(
                mutableSignals.tryEmit(
                    SubscriptionSignal.FetchCompleted(
                        outcomes = mapOf(*outcomes),
                        timedOut = false,
                    ),
                ),
            )
        }

        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) {
            this.filters = filters
            this.target = target
            filterUpdates += filters
        }

        override suspend fun close() {
            closed = true
        }
    }

    private fun event(
        id: String,
        createdAt: Long,
        kind: Int = 1,
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(
        id = id,
        pubkey = "author",
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = id,
        sig = "signature",
    )

    private fun supportedCommentTags(rootKind: Int = 1): List<List<String>> = listOf(
        listOf("E", "root", "", "root-author"),
        listOf("K", rootKind.toString()),
        listOf("P", "root-author"),
        listOf("e", "parent", "", "parent-author"),
        listOf("k", "1"),
        listOf("p", "parent-author"),
    )

    private fun NostrFilter.targetIds(): List<String>? = eTags ?: rootEventTags ?: qTags
}
