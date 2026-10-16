package com.nostr.torinos.ui.feed

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.AccountSessionResources
import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.engagement.EngagementStore
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayLimits
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.RetryDisposition
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.ui.timeline.NoteDeletion
import com.nostr.torinos.ui.timeline.NoteDeletionSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
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
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedControllerAsyncRegressionTest {
    @kotlin.test.BeforeTest
    fun clearSharedEngagementStore() = com.nostr.torinos.engagement.EngagementStore.shared.clearForTest()

    @Test
    fun timelineComputationDoesNotOverwriteStateChangedWhileOffMainThread() = runTest {
        val gateway = FakeGateway()
        val computeDispatcher = QueuedDispatcher()
        val controller = FeedController(
            computeDispatcher = computeDispatcher,
            scope = backgroundScope,
            subscriptions = gateway,
        )
        runCurrent()

        val author = "author-updated-during-computation"
        // 過去分の取得前に届くライブイベントは、取得開始時刻以降のものに限られる。
        val liveAt = Clock.System.now().epochSeconds + 10
        gateway.liveSessions.single().event(event("note", liveAt, pubkey = author))
        runCurrent()
        advanceTimeBy(150)
        runCurrent()

        // 1回目の計算だけを完了させ、FeedController側の継続はまだ再開しない。
        computeDispatcher.runNext()
        val profile = NostrProfile(name = "updated-during-computation")
        controller.injectProfile(author, profile)

        // 古い計算結果を破棄させ、最新UiStateを使った再計算を完了させる。
        runCurrent()
        computeDispatcher.runNext()
        runCurrent()

        assertEquals(listOf("note"), controller.state.value.events.map { it.id })
        assertEquals(profile, controller.state.value.profiles[author])
        controller.close()
    }

    @Test
    fun longBackgroundResetClearsTimelineAndStartsFromLatestOnlyOnce() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
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

        advanceTimeBy(999)
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
        // 応答リレーの短いページは未終端。次の空ページで69秒までの表示境界を確定する。
        controller.loadMore()
        runCurrent()
        gateway.feedFetchSessions.last { it.target == RelayTarget.Single("relay-up") }.complete("relay-up")
        runCurrent()
        assertTrue(controller.state.value.events.any { it.id == "from-slow-relay" })
        controller.close()
    }

    @Test
    fun initialEmptyStateWaitsForSlowRelayAndLateEventBecomesContent() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-up", "relay-down"))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
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

        val secondPageB = gateway.fetchSessions.last { it.target == RelayTarget.Single("relay-b") }
        assertTrue(secondPageB.filters.all { it.until == 71L })
        (42L..71L).forEach { createdAt ->
            secondPage.event(event("event-$createdAt", createdAt), relay = "relay-a")
            secondPageB.event(event("event-$createdAt", createdAt), relay = "relay-b")
        }
        secondPage.complete("relay-a")
        // 表示境界は両リレーが 42 秒まで網羅してから進む
        secondPageB.complete("relay-b")
        runCurrent()

        assertEquals(59, controller.state.value.events.size)
        controller.close()
    }

    @Test
    fun sameSecondBoundaryExpandsLimitBeforeAdvancingCursor() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
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
        assertEquals(3, gateway.feedFetchSessions.size)
        assertTrue(expandedPage.filters.all { it.until == 100L && it.limit == 60 })
        repeat(60) { index ->
            expandedPage.event(event("same-second-$index", 100))
        }
        expandedPage.complete()
        runCurrent()

        assertEquals(60, controller.state.value.events.size)
        controller.loadMore()
        runCurrent()
        assertTrue(gateway.feedFetchSessions.last().filters.all { it.until == 100L && it.limit == 120 })
        controller.close()
    }

    @Test
    fun relayRemovedAfterPageStartsDoesNotRemainPendingForever() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-up", "relay-removed"))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val firstPage = gateway.fetchSessions.single { it.target == RelayTarget.Single("relay-up") }

        gateway.relayUrls.value = setOf("relay-up")
        runCurrent()
        firstPage.event(event("from-up", 100), relay = "relay-up")
        firstPage.complete("relay-up")
        runCurrent()

        assertTrue(controller.state.value.canLoadMore)
        controller.loadMore()
        runCurrent()
        gateway.feedFetchSessions.last().complete("relay-up")
        runCurrent()
        assertFalse(controller.state.value.canLoadMore)
        assertEquals(3, gateway.feedFetchSessions.size)
        controller.close()
    }

    @Test
    fun relayAddedLaterStartsFromTheOriginalHistoryFloor() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-a"))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined,
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
        // コメントの短い非空ページも継続する。投稿の境界をコメントに流用しない。
        assertEquals(listOf(COMMENT_EVENT_KIND), nextPage.filters.single().kinds)
        assertEquals(90L, nextPage.filters.single().until)
        nextPage.complete("relay")
        runCurrent()
        controller.loadMore()
        runCurrent()
        val postPage = gateway.fetchSessions.last()
        assertEquals(listOf(1), postPage.filters.single().kinds)
        assertEquals(71L, postPage.filters.single().until)
        controller.close()
    }

    @Test
    fun structuralRelayRefusalIsolatesTheRejectedLogicalFilter() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined,
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined,
            includeRepliesInFeed = true,
            scope = backgroundScope,
            subscriptions = gateway,
            feedEventKinds = setOf(1, COMMENT_EVENT_KIND),
        )
        runCurrent()
        gateway.completeInitialFeedHistory()
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined,
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined,
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val firstPage = gateway.fetchSessions.single()
        repeat(30) { index -> firstPage.event(event("initial-$index", 100L - index)) }
        firstPage.complete()
        runCurrent()

        controller.loadMore()
        runCurrent()
        val nextPage = gateway.fetchSessions.last()
        val duplicate = event("shared", 60)
        gateway.liveSessions.single { !it.id.startsWith("reac-") }
            .event(duplicate, relay = "live-relay")
        nextPage.event(duplicate, relay = "relay")
        nextPage.complete("relay")
        runCurrent()

        assertEquals(1, controller.state.value.events.count { it.id == duplicate.id })
        controller.close()
    }

    @Test
    fun sameNostrEventFromMultipleRelaysIsAppliedOnce() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
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
    fun initialEngagementHistoryStartsBeforeEveryFeedRelaySettles() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-a", "relay-b"))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val relayAHistory = gateway.feedFetchSessions.single {
            it.target == RelayTarget.Single("relay-a")
        }

        relayAHistory.event(event("note", 10), relay = "relay-a")
        runCurrent()

        assertEquals(2, gateway.engagementFetchSessions.size)
        assertEquals(
            setOf(
                RelayTarget.Explicit(setOf("relay-a")),
                RelayTarget.Explicit(setOf("relay-b")),
            ),
            gateway.engagementFetchSessions.map { it.target }.toSet(),
        )
        assertTrue(
            gateway.engagementFetchSessions.all { history ->
                history.filters.all { it.targetIds() == listOf("note") }
            },
        )
        assertTrue(gateway.feedFetchSessions.any { !it.closed && it !== relayAHistory })
        controller.close()
    }

    @Test
    fun reactionReceivedBeforeHistoryPageCommitSurvivesTimelineRebuild() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-a", "relay-b"))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val relayAHistory = gateway.feedFetchSessions.single { it.target == RelayTarget.Single("relay-a") }
        val relayBHistory = gateway.feedFetchSessions.single { it.target == RelayTarget.Single("relay-b") }

        // relay-a はまだ EOSE 前。この時点で付随情報の取得が始まり、先にリアクションが届く。
        relayAHistory.event(event("note", 10), relay = "relay-a")
        runCurrent()
        val reaction = event(id = "reaction", createdAt = 20, kind = 7, tags = listOf(listOf("e", "note")))
        gateway.engagementFetchSessions.forEach { it.event(reaction) }
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        // 先に relay-b のページが確定し、一覧の組み直しが走る。
        relayBHistory.complete("relay-b")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        relayAHistory.complete("relay-a")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("note"), controller.state.value.events.map { it.id })
        assertEquals(1, controller.state.value.reactionCounts["note"])
        controller.close()
    }

    @Test
    fun hiddenReplyInHistoryPageFetchesOnlyItsParentEngagement() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val history = gateway.feedFetchSessions.single()

        history.event(event("reply", 10, tags = listOf(listOf("e", "parent", "", "reply"))))
        runCurrent()
        assertTrue(gateway.engagementFetchSessions.isEmpty(), "表示しない返信の付随情報を取りに行っている")

        history.complete()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        val requested = gateway.engagementFetchSessions.flatMap { session -> session.filters.mapNotNull { it.targetIds() } }.flatten().toSet()
        assertEquals(setOf("parent"), requested)
        controller.close()
    }

    @Test
    fun parallelRelayHistoryDoesNotDoubleCountTheSameReaction() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-a", "relay-b"))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        gateway.liveSessions.single().event(event("note", 10))
        runCurrent()

        val histories = gateway.engagementFetchSessions
        assertEquals(2, histories.size)
        val reaction = event(
            id = "reaction",
            createdAt = 20,
            kind = 7,
            tags = listOf(listOf("e", "note")),
        )
        histories.forEach { it.event(reaction) }
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertEquals(1, controller.state.value.reactionCounts["note"])
        controller.close()
    }

    @Test
    fun singleRelayHistoryContinuesWithTheNextBatchWithoutExtraDelay() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay"))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val feedLive = gateway.liveSessions.single()
        repeat(25) { index -> feedLive.event(event("note-$index", index.toLong())) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        val firstHistory = gateway.engagementFetchSessions.single()
        assertTrue(firstHistory.filters.all { it.targetIds()?.size == 20 })
        firstHistory.complete()
        runCurrent()

        assertEquals(2, gateway.engagementFetchSessions.size)
        assertTrue(
            gateway.engagementFetchSessions.last().filters.all {
                it.targetIds() == (20 until 25).map { index -> "note-$index" }
            },
        )
        controller.close()
    }

    @Test
    fun engagementHistoryFetchOnlyContainsNewlyWatchedIds() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
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
        assertEquals(1, gateway.engagementFetchSessions.size)
        advanceTimeBy(499)
        runCurrent()
        assertEquals(1, gateway.engagementFetchSessions.size)
        advanceTimeBy(1)
        runCurrent()

        val secondHistory = gateway.engagementFetchSessions.last()
        assertEquals(2, gateway.engagementFetchSessions.size)
        assertTrue(secondHistory.filters.all { it.since == null && it.until != null })
        assertTrue(secondHistory.filters.all { it.targetIds() == listOf("second") })

        // ライブ購読の対象の更新はまとめてから行う。since は重複期間を持つので、遅れても取りこぼさない。
        advanceTimeBy(500)
        runCurrent()
        val engagementLive = gateway.engagementLiveSessions.single()
        assertTrue(engagementLive.filters.all { it.since != null && it.until == null })
        assertTrue(engagementLive.filters.all { it.targetIds() == listOf("first", "second") })
        controller.close()
    }

    @Test
    fun liveEngagementSubscriptionWatchesOnlyNewestEvents() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        val feedLive = gateway.liveSessions.single()

        repeat(60) { index -> feedLive.event(event("note-$index", 100L + index)) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        // 新しい順に40件だけがライブ購読の対象になり、古い20件は履歴取得だけで扱う。
        val engagementLive = gateway.engagementLiveSessions.single()
        val expected = (20 until 60).map { "note-$it" }.toSet()
        assertTrue(engagementLive.filters.all { it.targetIds()?.toSet() == expected })
        val historyIds = gateway.engagementFetchSessions
            .flatMap { session -> session.filters.mapNotNull { it.targetIds() } }
            .flatten()
            .toSet()
        assertTrue((0 until 20).all { "note-$it" in historyIds })
        controller.close()
    }

    @Test
    fun loadingOlderEventsDoesNotResendLiveEngagementSubscription() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        val feedLive = gateway.liveSessions.single()

        repeat(50) { index -> feedLive.event(event("new-$index", 1_000L + index)) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        val engagementLive = gateway.engagementLiveSessions.single()
        val updatesBefore = engagementLive.filterUpdates.size
        val idsBefore = engagementLive.filters.first().targetIds()?.toSet()

        // スクロールで古い投稿が増えても、新しい40件が変わらなければ再送しない。
        repeat(10) { index -> feedLive.event(event("old-$index", 1L + index)) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertEquals(updatesBefore, engagementLive.filterUpdates.size)
        assertEquals(idsBefore, engagementLive.filters.first().targetIds()?.toSet())
        controller.close()
    }

    @Test
    fun olderEventsLoadedWhileScrolledDownAreNotCountedAsNewPosts() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        val feedLive = gateway.liveSessions.single()

        repeat(5) { index -> feedLive.event(event("top-$index", 1_000L + index)) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        // 下へスクロール済み。過去ページ(最上部より古い投稿)が読み込まれても、新着ではない。
        controller.setAtTop(false)
        repeat(3) { index -> feedLive.event(event("old-$index", 10L + index)) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, controller.state.value.newPostCount)

        // 最上部より新しい投稿だけが新着として数えられる。
        feedLive.event(event("fresh", 2_000L))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, controller.state.value.newPostCount)
        controller.close()
    }

    @Test
    fun newPostCountResetsWhenReturningToTop() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        val feedLive = gateway.liveSessions.single()

        feedLive.event(event("top", 1_000L))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        controller.setAtTop(false)
        feedLive.event(event("fresh", 2_000L))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, controller.state.value.newPostCount)

        controller.setAtTop(true)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, controller.state.value.newPostCount)
        controller.close()
    }

    @Test
    fun engagementHistoryWaitsForRelayRoutingButNotFeedHistoryCompletion() = runTest {
        val gateway = FakeGateway(initialRelayUrls = setOf("relay-a")).apply {
            targetRelayUrlsOverride = emptySet()
        }
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined,
            relayUrl = "relay-b",
            scope = backgroundScope,
            subscriptions = gateway,
        )
        runCurrent()
        val feedLive = gateway.liveSessions.single()

        feedLive.event(event("note", 10), relay = "relay-b")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertTrue(gateway.engagementFetchSessions.isEmpty())

        gateway.targetRelayUrlsOverride = setOf("relay-b")
        gateway.relayUrls.value = setOf("relay-b")
        runCurrent()

        val history = gateway.engagementFetchSessions.single()
        assertEquals(RelayTarget.Explicit(setOf("relay-b")), history.target)
        assertTrue(history.filters.all { it.targetIds() == listOf("note") })
        controller.close()
    }

    @Test
    fun overlappingHistoryAndLiveEngagementIsAppliedOnce() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        gateway.liveSessions.single().event(event("note", 10))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        gateway.engagementFetchSessions.forEach { history ->
            val relay = (history.target as RelayTarget.Explicit).urls.single()
            history.complete(relay)
        }
        runCurrent()

        gateway.relayUrls.value = setOf("relay-a")
        runCurrent()
        gateway.relayUrls.value = setOf("relay-a", "relay-b")
        runCurrent()

        assertEquals(3, gateway.engagementFetchSessions.size)
        assertEquals(
            RelayTarget.Explicit(setOf("relay-b")),
            gateway.engagementFetchSessions.last().target,
        )
        controller.close()
    }

    @Test
    fun completedEngagementHistoryIsNotFetchedAgainOnResume() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
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
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
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

    @Test
    fun timedOutEngagementHistoryIsRetriedAutomatically() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        gateway.liveSessions.single().event(event("note", 10))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        gateway.engagementFetchSessions.single().complete(timedOut = true)
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, gateway.engagementFetchSessions.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, gateway.engagementFetchSessions.size)
        assertTrue(gateway.engagementFetchSessions.last().filters.all { it.targetIds() == listOf("note") })
        controller.close()
    }

    @Test
    fun retriedEngagementHistoryDoesNotDoubleCountReceivedReaction() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
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

        val firstHistory = gateway.engagementFetchSessions.single()
        firstHistory.event(reaction)
        firstHistory.complete(timedOut = true)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        val retryHistory = gateway.engagementFetchSessions.last()
        retryHistory.event(reaction)
        retryHistory.complete()
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertEquals(1, controller.state.value.reactionCounts["note"])
        controller.close()
    }

    @Test
    fun structuralEngagementRefusalRetriesOneFilterAtATime() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        gateway.liveSessions.single().event(event("note", 10))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        val combined = gateway.engagementFetchSessions.single()
        assertTrue(combined.filters.size > 1)
        combined.closed("relay", RetryDisposition.RetryOnFilterChange)
        combined.completeWith("relay" to RelayOutcome.Closed("unsupported: filters"))
        runCurrent()

        assertEquals(2, gateway.engagementFetchSessions.size)
        assertEquals(1, gateway.engagementFetchSessions.last().filters.size)
        controller.close()
    }

    @Test
    fun nonRetryableEngagementRefusalDoesNotRepeatTheRequest() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        gateway.liveSessions.single().event(event("note", 10))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        val first = gateway.engagementFetchSessions.single()
        first.closed("relay", RetryDisposition.DoNotRetry)
        first.completeWith("relay" to RelayOutcome.Closed("blocked: not allowed"))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        // 拒否されたリレーへ、同じ取得を直ちに、あるいは繰り返し送らない。
        assertEquals(1, gateway.engagementFetchSessions.size)
        controller.close()
    }

    @Test
    fun refusedEngagementRelayIsRetriedAfterResume() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        gateway.liveSessions.single().event(event("note", 10))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        val first = gateway.engagementFetchSessions.single()
        first.closed("relay", RetryDisposition.DoNotRetry)
        first.completeWith("relay" to RelayOutcome.Closed("auth-required: sign in"))
        runCurrent()
        assertEquals(1, gateway.engagementFetchSessions.size)

        // 復帰(購読の停止と再開)で、停止していたリレーへの取得を再び試す。
        controller.stopSubscriptions()
        runCurrent()
        controller.startSubscriptions()
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertTrue(gateway.engagementFetchSessions.size >= 2)
        controller.close()
    }

    @Test
    fun engagementHistoryLimitsEachBatchToTwentyEvents() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        val feedLive = gateway.liveSessions.single()
        repeat(25) { index -> feedLive.event(event("note-$index", index.toLong())) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        val firstHistory = gateway.engagementFetchSessions.single()
        assertTrue(firstHistory.filters.all { it.targetIds()?.size == 20 })
        controller.close()
    }

    @Test
    fun engagementHistoryBackfillContinuesForEventsEvictedFromLiveTracking() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        val feedLive = gateway.liveSessions.single()

        // MAX_TRACKED_ENGAGEMENT_EVENTS(100件)を超えて投稿を追加し、
        // 最初期の投稿をwatchedEventIdsのFIFO上限から追い出す。
        repeat(120) { index -> feedLive.event(event("note-$index", index.toLong())) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        // 追跡上限で外れた最初の20件(note-0..note-19)も、取得が完了していない限り
        // バックフィル対象であり続けるべき。取得済みかどうかの記録がwatchedEventIdsの
        // FIFO削除と一緒に消えていると、note-20..note-39が選ばれてしまい、
        // 最初期の投稿は二度と取得されなくなる。
        val firstHistory = gateway.engagementFetchSessions.single()
        val expectedIds = (0 until 20).map { "note-$it" }
        assertTrue(firstHistory.filters.all { it.targetIds() == expectedIds })
        controller.close()
    }

    @Test
    fun reactionForEventEvictedFromLiveTrackingIsStillApplied() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        val feedLive = gateway.liveSessions.single()

        // targetを追加した直後に100件のfillerを追加し、targetだけを
        // watchedEventIdsのFIFO上限からちょうど1件だけ追い出す。
        feedLive.event(event("target", 0))
        repeat(100) { index -> feedLive.event(event("filler-$index", (index + 1).toLong())) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        // 投稿自体はまだフィードに表示されている(追跡対象から外れただけ)。
        assertTrue(controller.state.value.events.any { it.id == "target" })

        // バックフィル(手動更新 refreshReactions() も内部的には同じ経路)からtargetへの
        // リアクションが届いても、watchedEventIdsから外れているという理由だけで
        // 握りつぶされてはならない。
        val reaction = event("reaction-1", 200, kind = 7, tags = listOf(listOf("e", "target")))
        gateway.engagementFetchSessions.single().event(reaction)
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertEquals(1, controller.state.value.reactionCounts["target"])
        controller.close()
    }

    @Test
    fun eventsReceivedByAnotherScreenAreNotCountedAgainWhenFetchedLater() = runTest {
        val gateway = FakeGateway()
        val store = EngagementStore()
        val controller = controllerTrackingTarget(gateway, store = store)

        // 詳細画面で数えた2件が先に届き、その後で同じ2件と新しい1件をリレーから受け取る。
        observeElsewhere(store, reaction("r1", "🤙"), reaction("r2", "+"))
        assertEquals(2, controller.state.value.reactionCounts["target"])
        deliverEngagement(gateway, reaction("r1", "🤙"), reaction("r2", "+"), reaction("r3", "🤙"))

        val state = controller.state.value
        assertEquals(3, state.reactionCounts["target"])
        assertEquals(1, state.likeReactionCounts["target"])
        assertEquals(2, state.unicodeReactions["target"].orEmpty().single { it.content == "🤙" }.count)
        controller.close()
    }

    @Test
    fun eventsReceivedByAnotherScreenDoNotDropEventsOnlyTheFeedReceived() = runTest {
        val gateway = FakeGateway()
        val store = EngagementStore()
        val controller = controllerTrackingTarget(gateway, store = store)
        deliverEngagement(gateway, reaction("feed-only", "🤙"), reaction("r1", "🤙"))

        observeElsewhere(store, reaction("r1", "🤙"), reaction("r2", "+"))

        assertEquals(3, controller.state.value.reactionCounts["target"])
        controller.close()
    }

    @Test
    fun ownReactionArrivingLaterIsCountedOnce() = runTest {
        val gateway = FakeGateway()
        val store = EngagementStore()
        val controller = controllerTrackingTarget(gateway, ownSession(), store)

        // 自分のリアクションは、送信が終わってリレーから届いた時点で数える。
        observeElsewhere(store, reaction("r1", "🤙"))
        assertEquals(1, controller.state.value.reactionCounts["target"])
        deliverEngagement(gateway, reaction("r1", "🤙"), reaction("mine", "+", pubkey = OWN_PUBKEY))

        val state = controller.state.value
        assertEquals(2, state.reactionCounts["target"])
        assertEquals("mine", state.likedReactions["target"])
        controller.close()
    }

    @Test
    fun repliesRepostsAndQuotesFromAnotherScreenMergeById() = runTest {
        val gateway = FakeGateway()
        val store = EngagementStore()
        val controller = controllerTrackingTarget(gateway, store = store)
        val feedReply = event("feed-reply", 100, tags = listOf(listOf("e", "target", "", "root")))
        val threadReply = event("thread-reply", 101, tags = listOf(listOf("e", "target", "", "root")))
        val feedRepost = event("feed-repost", 102, kind = 6, tags = listOf(listOf("e", "target")))
        val threadRepost = event("thread-repost", 103, kind = 6, tags = listOf(listOf("e", "target")))
        val threadQuote = event("thread-quote", 104, tags = listOf(listOf("q", "target")))
        deliverEngagement(gateway, feedReply, feedRepost)

        observeElsewhere(store, threadReply, threadRepost, threadQuote)
        assertEquals(2, controller.state.value.replyCounts["target"])
        assertEquals(3, controller.state.value.repostCounts["target"])
        deliverEngagement(gateway, threadReply, threadRepost, threadQuote)

        val state = controller.state.value
        assertEquals(2, state.replyCounts["target"])
        assertEquals(setOf("feed-reply", "thread-reply"), state.replies["target"].orEmpty().map { it.id }.toSet())
        assertEquals(3, state.repostCounts["target"])
        controller.close()
    }

    @Test
    fun ownRepostDeletionIsSubtractedFromFeed() = runTest {
        val gateway = FakeGateway()
        val store = EngagementStore()
        val controller = controllerTrackingTarget(gateway, ownSession(), store)
        val ownRepost = event("my-repost", 100, kind = 6, tags = listOf(listOf("e", "target")), pubkey = OWN_PUBKEY)
        deliverEngagement(gateway, ownRepost, event("other-repost", 101, kind = 6, tags = listOf(listOf("e", "target"))))
        assertEquals("my-repost", controller.state.value.repostedEvents["target"])

        // 詳細画面などでの取り消しは kind 5 としてストアに届く。削除前のリレーから再送されても数えない。
        store.observe(event("del", 300, kind = 5, tags = listOf(listOf("e", "my-repost")), pubkey = OWN_PUBKEY), "relay")
        deliverEngagement(gateway, ownRepost)

        val state = controller.state.value
        assertEquals(1, state.repostCounts["target"])
        assertEquals(null, state.repostedEvents["target"])
        controller.close()
    }

    @Test
    fun reactionRedeliveredAfterSeenIdsRollOverIsNotCountedAgain() = runTest {
        val gateway = FakeGateway()
        val controller = controllerTrackingTarget(gateway)
        deliverEngagement(gateway, reaction("first", "🤙"))
        gateway.engagementFetchSessions.single().complete("relay")
        runCurrent()
        val live = gateway.engagementLiveSessions.single()
        // 重複排除用の既読IDは上限で古いものから捨てられる。上限を超える件数を受け取った後でも、
        // 数え済みのイベントが再送されたら重ねて数えてはならない。
        repeat(2_000) { live.event(reaction("other-$it", "+")) }
        runCurrent()

        live.event(reaction("first", "🤙"))
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertEquals(2_001, controller.state.value.reactionCounts["target"])
        controller.close()
    }

    @Test
    fun engagementHistoryFetchesOlderPageWhenAPartitionMayBeTruncated() = runTest {
        val gateway = FakeGateway()
        val controller = controllerTrackingTarget(gateway)
        val firstPage = gateway.engagementFetchSessions.single()

        // リレーが件数上限で打ち切った可能性がある件数が届いたら、最古の時刻から続きを取りにいく。
        repeat(ENGAGEMENT_PAGE_THRESHOLD) { index ->
            firstPage.event(reaction("r$index", "+", createdAt = 1_000L + index))
        }
        firstPage.complete("relay")
        runCurrent()

        val secondPage = gateway.engagementFetchSessions.last()
        assertTrue(secondPage !== firstPage)
        assertEquals(listOf(listOf(7)), secondPage.filters.map { it.kinds })
        assertEquals(1_000L, secondPage.filters.single().until)
        assertEquals(listOf("target"), secondPage.filters.single().eTags)

        secondPage.event(reaction("r0", "+", createdAt = 1_000L))
        secondPage.event(reaction("older", "+", createdAt = 900L))
        secondPage.complete("relay")
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertEquals(ENGAGEMENT_PAGE_THRESHOLD + 1, controller.state.value.reactionCounts["target"])
        assertEquals(2, gateway.engagementFetchSessions.size)
        controller.close()
    }

    @Test
    fun engagementHistoryPagingMovesPastASecondFilledWithEvents() = runTest {
        val gateway = FakeGateway()
        val controller = controllerTrackingTarget(gateway)
        val firstPage = gateway.engagementFetchSessions.single()

        repeat(ENGAGEMENT_PAGE_THRESHOLD) { index -> firstPage.event(reaction("r$index", "+", createdAt = 500L)) }
        firstPage.complete("relay")
        runCurrent()
        val secondPage = gateway.engagementFetchSessions.last()
        assertEquals(500L, secondPage.filters.single().until)

        // 同じ秒の続きでまた上限まで埋まったら、同じ until を繰り返さず1秒前へ進める。
        repeat(ENGAGEMENT_PAGE_THRESHOLD) { index -> secondPage.event(reaction("s$index", "+", createdAt = 500L)) }
        secondPage.complete("relay")
        runCurrent()

        assertEquals(499L, gateway.engagementFetchSessions.last().filters.single().until)
        controller.close()
    }

    @Test
    fun engagementHistoryStopsPagingWhenRelayIgnoresUntil() = runTest {
        val gateway = FakeGateway()
        val controller = controllerTrackingTarget(gateway)
        val firstPage = gateway.engagementFetchSessions.single()
        val newest = List(ENGAGEMENT_PAGE_THRESHOLD) { index -> reaction("r$index", "+", createdAt = 1_000L + index) }
        newest.forEach(firstPage::event)
        firstPage.complete("relay")
        runCurrent()

        // until より新しいものが返るなら、そのリレーは until を無視している。続きを要求しても同じ結果になる。
        val secondPage = gateway.engagementFetchSessions.last()
        newest.forEach(secondPage::event)
        secondPage.complete("relay")
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertEquals(2, gateway.engagementFetchSessions.size)
        assertEquals(ENGAGEMENT_PAGE_THRESHOLD, controller.state.value.reactionCounts["target"])
        controller.close()
    }

    @Test
    fun engagementLiveSubscriptionIsNotReopenedWhilePostsArriveOneByOne() = runTest {
        val gateway = FakeGateway()
        val gate = CompletableDeferred<Unit>().also { gateway.engagementLiveOpenGate = it }
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        val feedLive = gateway.liveSessions.single()

        // 起動直後に投稿が1件ずつ届く。開きかけの購読を取り消して開き直してはならない。
        repeat(5) { index ->
            feedLive.event(event("post-$index", index.toLong()))
            runCurrent()
            advanceTimeBy(10)
            runCurrent()
        }
        gate.complete(Unit)
        advanceTimeBy(2_000)
        runCurrent()

        val liveOpens = gateway.openAttempts.filter { it.id.startsWith("reac-") && it.behavior is SubscriptionBehavior.Live }
        assertEquals(1, liveOpens.size)
        val live = gateway.engagementLiveSessions.single()
        assertFalse(live.closed)
        assertEquals(
            (0 until 5).map { "post-$it" }.toSet(),
            (live.filterUpdates.lastOrNull() ?: live.filters).flatMap { it.targetIds().orEmpty() }.toSet(),
        )
        controller.close()
    }

    @Test
    fun engagementLiveUpdatesAreCoalesced() = runTest {
        val gateway = FakeGateway()
        val controller = controllerTrackingTarget(gateway)
        val live = gateway.engagementLiveSessions.single()
        val feedLive = gateway.liveSessions.first { it.id.startsWith("feed-") }

        // 投稿の到着と履歴取得の完了が交互に続いても、ライブ購読の更新はまとめて1回にする。
        // 履歴取得の完了ごとに監視対象が変わり、そのたびに更新すると同じ購読へのREQが続く。
        repeat(5) { index ->
            feedLive.event(event("new-$index", 10L + index))
            runCurrent()
            gateway.engagementFetchSessions.lastOrNull { !it.closed }?.let { history ->
                history.complete("relay")
                history.closed = true
            }
            runCurrent()
            advanceTimeBy(50)
            runCurrent()
        }
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(1, live.filterUpdates.size)
        assertEquals(
            setOf("target") + (0 until 5).map { "new-$it" },
            live.filterUpdates.single().flatMap { it.targetIds().orEmpty() }.toSet(),
        )
        controller.close()
    }

    @Test
    fun countsComeFromTheSharedStoreIncludingEventsReceivedByOtherScreens() = runTest {
        val gateway = FakeGateway()
        val store = EngagementStore()
        val controller = controllerTrackingTarget(gateway, store = store)

        // 詳細画面や通知など、フィード以外の購読で受信したイベントもストアに入る。フィードはそれを表示する。
        store.observe(reaction("from-thread", "🤙"), "relay")
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertEquals(1, controller.state.value.reactionCounts["target"])
        assertEquals(listOf("from-thread"), controller.state.value.reactionEvents["target"].orEmpty().map { it.id })
        controller.close()
    }

    @Test
    fun countsOfTrackedPostOutsideTheVisibleListSurviveTimelineRebuild() = runTest {
        val gateway = FakeGateway()
        val store = EngagementStore()
        val controller = FeedController(
            computeDispatcher = Dispatchers.Unconfined,
            scope = backgroundScope,
            subscriptions = gateway,
            engagementStore = store,
        )
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        val feedLive = gateway.liveSessions.single()
        // 一覧に出さない返信が届くと、返信先（一覧外）の付随情報を追跡する。
        feedLive.event(event("reply", 5, tags = listOf(listOf("e", "parent", "", "root"))))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        deliverEngagement(gateway, event("r1", 6, kind = 7, tags = listOf(listOf("e", "parent"))))
        assertEquals(1, controller.state.value.reactionCounts["parent"])

        // 新しい投稿で一覧を組み直しても、追跡中の投稿の件数は捨てない。
        feedLive.event(event("post", 10))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        gateway.engagementFetchSessions.last().event(event("r1", 6, kind = 7, tags = listOf(listOf("e", "parent"))))
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertEquals(1, controller.state.value.reactionCounts["parent"])
        controller.close()
    }

    @Test
    fun deletionReceivedFromAnyScreenRemovesOwnLike() = runTest {
        val gateway = FakeGateway()
        val store = EngagementStore()
        val controller = controllerTrackingTarget(gateway, ownSession(), store)
        deliverEngagement(gateway, reaction("mine", "+", pubkey = OWN_PUBKEY), reaction("r1", "🤙"))
        assertEquals("mine", controller.state.value.likedReactions["target"])

        store.observe(event("del", 300, kind = 5, tags = listOf(listOf("e", "mine")), pubkey = OWN_PUBKEY), "relay")
        runCurrent()
        advanceTimeBy(151)
        runCurrent()
        deliverEngagement(gateway, reaction("mine", "+", pubkey = OWN_PUBKEY))

        assertEquals(1, controller.state.value.reactionCounts["target"])
        assertEquals(0, controller.state.value.likeReactionCounts["target"] ?: 0)
        assertEquals(null, controller.state.value.likedReactions["target"])
        controller.close()
    }

    @Test
    fun engagementHistoryCountsOnlyEventsFromTheQueriedRelayWhenDecidingToPage() = runTest {
        val gateway = FakeGateway()
        val controller = controllerTrackingTarget(gateway)
        val firstPage = gateway.engagementFetchSessions.single()

        // キャッシュから再生されたものはリレーが返した件数ではない。打ち切りの判定に数えない。
        repeat(ENGAGEMENT_PAGE_THRESHOLD) { index ->
            firstPage.event(reaction("cached-$index", "+", createdAt = 1_000L + index), relay = "local://reaction-cache")
        }
        firstPage.event(reaction("fresh", "+", createdAt = 2_000L), relay = "relay")
        firstPage.complete("relay")
        runCurrent()

        assertEquals(1, gateway.engagementFetchSessions.size)
        controller.close()
    }

    @Test
    fun engagementHistoryAsksRelayDirectlyWithoutCacheReplayOrDeduplication() = runTest {
        val gateway = FakeGateway()
        val controller = controllerTrackingTarget(gateway)

        // リレーが返した件数で打ち切りを判定するため、同じイベントでもリレーからの分を受け取る。
        val spec = gateway.openAttempts.last { "-history-" in it.id }
        assertFalse(spec.deduplicateEvents)
        assertFalse(spec.replayCachedEvents)
        controller.close()
    }

    @Test
    fun engagementHistoryPagingUsesTheRelaysAdvertisedMaxLimit() = runTest {
        RelayLimits.clearForTest()
        RelayLimits.recordMaxLimit("relay", 200)
        val gateway = FakeGateway()
        val controller = controllerTrackingTarget(gateway)
        val firstPage = gateway.engagementFetchSessions.single()

        // max_limit が 200 と分かっていれば、120 件は打ち切りではない。
        repeat(120) { index -> firstPage.event(reaction("r$index", "+", createdAt = 1_000L + index)) }
        firstPage.complete("relay")
        runCurrent()
        assertEquals(1, gateway.engagementFetchSessions.size)
        assertEquals(listOf(200), firstPage.filters.map { it.limit }.distinct())

        RelayLimits.clearForTest()
        controller.close()
    }

    @Test
    fun screensTrackingTheSamePostShareOneHistoryFetch() = runTest {
        val gateway = FakeGateway()
        val fetcher = sharedFetcher(gateway)
        val feed = Any()
        val thread = Any()
        fetcher.register(feed, sortTimeOf = { null }, onEvent = {})
        fetcher.register(thread, sortTimeOf = { Long.MAX_VALUE }, onEvent = {})
        fetcher.activate(feed)
        fetcher.activate(thread)

        fetcher.track(feed, "target")
        fetcher.track(thread, "target")
        runCurrent()
        advanceTimeBy(600)
        runCurrent()

        // フィードとポスト詳細が同じ投稿を表示しても、履歴は1回だけ取りにいく。
        assertEquals(1, gateway.engagementFetchSessions.size)
        assertEquals(1, gateway.engagementLiveSessions.size)
        fetcher.release(feed)
        fetcher.release(thread)
    }

    @Test
    fun postStaysTrackedWhileAnotherScreenStillShowsIt() = runTest {
        val gateway = FakeGateway()
        val fetcher = sharedFetcher(gateway)
        val feed = Any()
        val thread = Any()
        fetcher.register(feed, sortTimeOf = { null }, onEvent = {})
        fetcher.register(thread, sortTimeOf = { null }, onEvent = {})
        fetcher.track(feed, "target")
        fetcher.track(thread, "target")

        fetcher.forget(feed, "target")
        assertTrue(fetcher.isTracked("target"))
        assertFalse(fetcher.isTrackedBy(feed, "target"))

        fetcher.forget(thread, "target")
        assertFalse(fetcher.isTracked("target"))
    }

    @Test
    fun liveSubscriptionStaysOpenUntilTheLastScreenStops() = runTest {
        val gateway = FakeGateway()
        val fetcher = sharedFetcher(gateway)
        val feed = Any()
        val thread = Any()
        fetcher.register(feed, sortTimeOf = { null }, onEvent = {})
        fetcher.register(thread, sortTimeOf = { null }, onEvent = {})
        fetcher.activate(feed)
        fetcher.activate(thread)
        fetcher.track(feed, "target")
        runCurrent()
        advanceTimeBy(600)
        runCurrent()
        val live = gateway.engagementLiveSessions.single()

        fetcher.deactivate(thread)
        runCurrent()
        assertFalse(live.closed)

        fetcher.deactivate(feed)
        runCurrent()
        assertTrue(live.closed)
    }

    @Test
    fun eventsFromSharedFetchGoToTheStoreAndEveryActiveScreen() = runTest {
        val gateway = FakeGateway()
        val store = EngagementStore()
        val fetcher = sharedFetcher(gateway, store)
        val received = mutableListOf<String>()
        val feed = Any()
        fetcher.register(feed, sortTimeOf = { null }, onEvent = { received += it.id })
        fetcher.activate(feed)
        store.retain(feed, setOf("target"))
        fetcher.track(feed, "target")
        runCurrent()
        advanceTimeBy(600)
        runCurrent()

        gateway.engagementFetchSessions.single().event(reaction("r1", "+"))
        runCurrent()

        assertEquals(listOf("r1"), received)
        assertEquals(setOf("r1"), store.snapshot("target")?.reactions?.keys)
        fetcher.release(feed)
    }

    private fun kotlinx.coroutines.test.TestScope.sharedFetcher(
        gateway: FakeGateway,
        store: EngagementStore = EngagementStore(),
    ): EngagementFetcher {
        val fetcher = EngagementFetcher(
            scope = backgroundScope,
            subscriptions = gateway,
            relayTarget = RelayTarget.AllEnabled,
            store = store,
            subscriptionIdPrefix = "reac-test",
        )
        runCurrent()
        return fetcher
    }

    private fun kotlinx.coroutines.test.TestScope.controllerTrackingTarget(
        gateway: FakeGateway,
        session: AccountSession? = null,
        store: EngagementStore = EngagementStore(),
    ): FeedController {
        val controller = FeedController(
            accountSession = session,
            computeDispatcher = Dispatchers.Unconfined,
            scope = backgroundScope,
            subscriptions = gateway,
            engagementStore = store,
        )
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        gateway.liveSessions.single().event(event("target", 0))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        return controller
    }

    private fun kotlinx.coroutines.test.TestScope.deliverEngagement(gateway: FakeGateway, vararg events: NostrEvent) {
        events.forEach { gateway.engagementFetchSessions.single().event(it) }
        runCurrent()
        advanceTimeBy(151)
        runCurrent()
    }

    /** フィード以外の画面（ポスト詳細など）が受信したイベント。共有ストアに入る。 */
    private fun kotlinx.coroutines.test.TestScope.observeElsewhere(store: EngagementStore, vararg events: NostrEvent) {
        events.forEach { store.observe(it, "relay") }
        runCurrent()
        advanceTimeBy(151)
        runCurrent()
    }

    private fun reaction(
        id: String,
        content: String,
        pubkey: String = "reactor-$id",
        createdAt: Long = 200,
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = 7,
        tags = listOf(listOf("e", "target"), listOf("p", "author")),
        content = content,
        sig = "signature",
    )

    private fun ownSession() = AccountSession(
        sessionId = OWN_SESSION_ID,
        pubkey = OWN_PUBKEY,
        signer = OwnSigner,
        resources = AccountSessionResources(),
    )

    private object OwnSigner : AccountSigner {
        override val pubkey: String = OWN_PUBKEY
        override suspend fun nip44Encrypt(plaintext: String, peerPubkey: String): String = plaintext
        override suspend fun nip44Decrypt(content: String, peerPubkey: String): String = content
        override suspend fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?) =
            NostrEvent(id = "signed", pubkey = OWN_PUBKEY, createdAt = 0, kind = kind, tags = tags, content = content, sig = "sig")
    }

    @Test
    fun deletedEventDiscardsEngagementHistoryState() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        val feedLive = gateway.liveSessions.single()

        feedLive.event(event("target", 0))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(gateway.engagementFetchSessions.single().filters.all { it.targetIds() == listOf("target") })

        NoteDeletionSync.publish(NoteDeletion(sessionId = null, eventId = "target"))
        runCurrent()
        assertFalse(controller.state.value.events.any { it.id == "target" })

        // 削除後に同じ投稿IDへのリアクションが届いても、もう追跡していない投稿なので
        // 適用されてはならない。適用されてしまう場合、削除時にwatchedEventIds /
        // engagementHistoryStatesが破棄されていない。
        val reaction = event("reaction-1", 200, kind = 7, tags = listOf(listOf("e", "target")))
        gateway.engagementFetchSessions.single().event(reaction)
        runCurrent()
        advanceTimeBy(151)
        runCurrent()

        assertEquals(null, controller.state.value.reactionCounts["target"])
        controller.close()
    }

    @Test
    fun updatingAuthorsRestartsFeedWithNewAuthorsInSameController() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(
            computeDispatcher = Dispatchers.Unconfined,
            authorPubkeys = listOf("alice"),
            scope = backgroundScope,
            subscriptions = gateway,
        )
        runCurrent()
        gateway.completeInitialFeedHistory()
        runCurrent()
        gateway.liveSessions.single().event(event("from-alice", 10, pubkey = "alice"))
        advanceTimeBy(151)
        runCurrent()
        assertEquals(listOf("from-alice"), controller.state.value.events.map { it.id })
        val sessionsBefore = gateway.sessions.toList()

        assertTrue(controller.updateAuthors(listOf("alice", "bob")))
        runCurrent()

        assertTrue(controller.state.value.events.isEmpty())
        assertTrue(controller.state.value.isInitialLoad)
        assertTrue(sessionsBefore.all { it.closed })
        val newFeedSessions = gateway.sessions.filter { it !in sessionsBefore && it.id.startsWith("feed-") }
        assertTrue(newFeedSessions.isNotEmpty())
        assertTrue(newFeedSessions.all { session -> session.filters.all { it.authors == listOf("alice", "bob") } })
        controller.close()
    }

    @Test
    fun updatingAuthorsWithSameListIsNoOp() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(
            computeDispatcher = Dispatchers.Unconfined,
            authorPubkeys = listOf("alice"),
            scope = backgroundScope,
            subscriptions = gateway,
        )
        runCurrent()
        val sessionCount = gateway.sessions.size

        assertFalse(controller.updateAuthors(listOf("alice")))
        runCurrent()

        assertEquals(sessionCount, gateway.sessions.size)
        assertTrue(gateway.sessions.none { it.closed })
        controller.close()
    }

    @Test
    fun updatingAuthorsWhileStoppedDefersSubscriptionUntilStart() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(
            computeDispatcher = Dispatchers.Unconfined,
            authorPubkeys = listOf("alice"),
            scope = backgroundScope,
            subscriptions = gateway,
        )
        runCurrent()
        controller.stopSubscriptions()
        runCurrent()
        val sessionCount = gateway.sessions.size

        assertTrue(controller.updateAuthors(listOf("bob")))
        runCurrent()
        assertEquals(sessionCount, gateway.sessions.size)

        controller.startSubscriptions()
        runCurrent()
        val restarted = gateway.feedFetchSessions.last()
        assertTrue(restarted.filters.all { it.authors == listOf("bob") })
        controller.close()
    }

    @Test
    fun shortNonEmptyPageStillRequestsOlderHistory() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val first = gateway.fetchSessions.single()
        repeat(20) { first.event(event("short-$it", 100L - it)) }
        first.complete()
        runCurrent()
        assertTrue(controller.state.value.canLoadMore)
        controller.loadMore()
        runCurrent()
        val second = gateway.feedFetchSessions.last()
        assertEquals(81L, second.filters.single().until)
        second.event(event("older-than-short-page", 70))
        second.complete()
        runCurrent()
        assertTrue(controller.state.value.events.any { it.id == "older-than-short-page" })
        controller.close()
    }

    @Test
    fun failedPageStorageDoesNotAdvanceCursorAndCanBeRetried() = runTest {
        val gateway = FakeGateway()
        val store = object : FeedHistoryStore() {
            var fail = true
            override fun commit(scopeGeneration: Int, page: List<StoredFeedEvent>): FeedStoreCommitResult =
                if (fail) FeedStoreCommitResult.Failed else super.commit(scopeGeneration, page)
        }
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope,
            subscriptions = gateway, historyStore = store)
        runCurrent()
        val first = gateway.feedFetchSessions.single()
        val floor = first.filters.single().until
        first.event(event("saved-after-retry", 100))
        first.complete()
        runCurrent()
        assertTrue(controller.state.value.storageFailure)
        assertTrue(controller.state.value.events.isEmpty())
        store.fail = false
        controller.retryHistory()
        runCurrent()
        val retried = gateway.feedFetchSessions.last()
        assertEquals(floor, retried.filters.single().until)
        retried.event(event("saved-after-retry", 100))
        retried.complete()
        runCurrent()
        assertFalse(controller.state.value.storageFailure)
        assertEquals(listOf("saved-after-retry"), controller.state.value.events.map { it.id })
        controller.close()
    }

    @Test
    fun largeSameSecondPageCanBeBrowsedAcrossStoredWindows() = runTest {
        val gateway = FakeGateway()
        val store = FeedHistoryStore()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope,
            subscriptions = gateway, historyStore = store)
        runCurrent()
        val page = gateway.feedFetchSessions.single()
        repeat(3_840) { page.event(event("large-${it.toString().padStart(4, '0')}", 100)) }
        page.complete()
        runCurrent()
        assertEquals(3_840, store.snapshot.size)
        assertEquals(800, controller.state.value.events.size)
        val seen = mutableSetOf<String>()
        repeat(12) {
            seen += controller.state.value.events.map { it.id }
            if (controller.state.value.hasOlderStoredEvents) controller.loadMore()
            runCurrent()
        }
        assertEquals(3_840, seen.size)
        assertTrue(controller.state.value.hasNewerStoredEvents)
        val fetchCount = gateway.feedFetchSessions.size
        controller.loadNewerStored()
        runCurrent()
        assertEquals(fetchCount, gateway.feedFetchSessions.size)
        controller.close()
    }

    @Test
    fun liveEventsAfterLocalLimitAreTrimmedInBatches() = runTest {
        val gateway = FakeGateway()
        val store = object : FeedHistoryStore(softLimit = 1_000, liveTrimSlack = 5) {
            var trims = 0
            override fun trimLive(protectedIds: Set<String>): List<StoredFeedEvent> {
                trims++
                return super.trimLive(protectedIds)
            }
        }
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope,
            subscriptions = gateway, historyStore = store)
        runCurrent()
        val page = gateway.feedFetchSessions.single()
        repeat(1_000) { page.event(event("stored-$it", 1_000L - it)) }
        page.complete()
        runCurrent()
        assertTrue(store.limitReached)
        val live = gateway.liveSessions.single { !it.id.startsWith("reac-") }
        repeat(12) { live.event(event("live-$it", 2_000L + it)) }
        runCurrent()
        // 1件ごとではなく、余裕分（5件）を超えるたびに上限まで削る。削るのは表示外の古い投稿から。
        assertEquals(2, store.trims)
        assertEquals(1_000, store.snapshot.size)
        assertFalse(store.snapshot.any { it.event.id == "stored-999" })
        // 表示待ちだったライブの投稿は削られずに表示される。
        advanceTimeBy(1_000)
        runCurrent()
        val shown = controller.state.value.events.map { it.id }
        assertEquals((11 downTo 0).map { "live-$it" }, shown.take(12))
        controller.close()
    }

    @Test
    fun pageReachingLocalLimitIsFullySavedAndStopsNewRequests() = runTest {
        val gateway = FakeGateway()
        val store = FeedHistoryStore(softLimit = 10)
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope,
            subscriptions = gateway, historyStore = store)
        runCurrent()
        val page = gateway.feedFetchSessions.single()
        repeat(30) { page.event(event("limit-$it", 100L - it)) }
        page.complete()
        runCurrent()
        assertEquals(30, store.snapshot.size)
        assertTrue(controller.state.value.localHistoryLimitReached)
        assertFalse(controller.state.value.canLoadMore)
        controller.loadMore()
        runCurrent()
        assertEquals(1, gateway.feedFetchSessions.size)
        controller.resetHistory()
        runCurrent()
        assertFalse(controller.state.value.localHistoryLimitReached)
        assertTrue(store.snapshot.isEmpty())
        controller.close()
    }

    @Test
    fun returningToNewestStoredWindowShowsLaterLiveEvents() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val page = gateway.feedFetchSessions.single()
        repeat(1_000) { page.event(event("stored-${it.toString().padStart(4, '0')}", 100)) }
        page.complete()
        runCurrent()
        controller.loadMore()
        runCurrent()
        assertTrue(controller.state.value.hasNewerStoredEvents)
        controller.loadNewerStored()
        runCurrent()
        assertFalse(controller.state.value.hasNewerStoredEvents)
        val now = Clock.System.now().epochSeconds
        gateway.liveSessions.first { it.id.startsWith("feed-") }.event(event("live-after-window", now))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("live-after-window", controller.state.value.events.first().id)
        controller.close()
    }

    @Test
    fun knownBoundarySecondAtHistoryEndStepsBackWithoutCoverageGap() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        val first = gateway.feedFetchSessions.single()
        repeat(30) { first.event(event("full-$it", 100L - it)) }
        first.complete()
        runCurrent()
        controller.loadMore()
        runCurrent()
        // 履歴の末尾では既知の境界秒だけが返る。要求数を拡張せず、前の秒へ進む。
        val boundary = gateway.feedFetchSessions.last()
        assertEquals(71L, boundary.filters.single().until)
        boundary.event(event("full-29", 71))
        boundary.complete()
        runCurrent()
        val older = gateway.feedFetchSessions.last()
        assertEquals(70L, older.filters.single().until)
        assertEquals(30, older.filters.single().limit)
        older.complete()
        runCurrent()
        assertFalse(controller.state.value.canLoadMore)
        assertEquals(0, controller.state.value.coverageGapCount)
        assertEquals(3, gateway.feedFetchSessions.size)
        controller.close()
    }

    @Test
    fun cappedRelayBoundaryIsRecordedWithoutFutileExpansion() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        // 要求数30に対し、常に20件までしか返さないリレー。
        val first = gateway.feedFetchSessions.single()
        repeat(20) { first.event(event("capped-$it", 100L - it)) }
        first.complete()
        runCurrent()
        controller.loadMore()
        runCurrent()
        val boundary = gateway.feedFetchSessions.last()
        assertEquals(81L, boundary.filters.single().until)
        repeat(20) { boundary.event(event("same-second-$it", 81)) }
        boundary.complete()
        runCurrent()
        assertEquals(1, controller.state.value.coverageGapCount)
        assertTrue(controller.state.value.canLoadMore)
        controller.loadMore()
        runCurrent()
        assertEquals(80L, gateway.feedFetchSessions.last().filters.single().until)
        controller.close()
    }

    @Test
    fun hiddenOnlyPagesStopAutoContinueUntilExplicitLoadMore() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        runCurrent()
        var at = 1_000L
        // 返信だけのページは表示されない。自動継続は上限で止まる。
        repeat(5) { page ->
            val session = gateway.feedFetchSessions.last()
            assertEquals(page + 1, gateway.feedFetchSessions.size)
            repeat(30) { session.event(event("reply-$page-$it", at--, tags = listOf(listOf("e", "parent", "", "reply")))) }
            session.complete()
            runCurrent()
        }
        assertEquals(5, gateway.feedFetchSessions.size)
        assertTrue(controller.state.value.requiresExplicitLoadMore)
        controller.loadMore(explicit = false)
        runCurrent()
        assertEquals(5, gateway.feedFetchSessions.size)
        controller.loadMore(explicit = true)
        runCurrent()
        assertEquals(6, gateway.feedFetchSessions.size)
        assertFalse(controller.state.value.requiresExplicitLoadMore)
        controller.close()
    }

    @Test
    fun oldScopePageIsNotShownAfterAuthorsChange() = runTest {
        val gateway = FakeGateway()
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = gateway)
        controller.updateAuthors(listOf("old-author"))
        controller.startSubscriptions()
        runCurrent()
        val oldPage = gateway.feedFetchSessions.last()
        controller.updateAuthors(listOf("new-author"))
        runCurrent()
        oldPage.event(event("from-old-scope", 100, pubkey = "old-author"))
        oldPage.complete()
        runCurrent()
        assertFalse(controller.state.value.events.any { it.id == "from-old-scope" })
        controller.close()
    }

    private class FakeGateway(initialRelayUrls: Set<String> = setOf("relay")) : FeedSubscriptionGateway {
        val relayUrls = MutableStateFlow(initialRelayUrls)
        var targetRelayUrlsOverride: Set<String>? = null
        val sessions = mutableListOf<FakeSession>()
        val subscribedFilters = mutableListOf<NostrFilter>()
        val fetchSessions: List<FakeSession>
            get() = sessions.filter { it.behavior is SubscriptionBehavior.Fetch }
        val liveSessions: List<FakeSession>
            get() = sessions.filter { it.behavior is SubscriptionBehavior.Live }
        val engagementFetchSessions: List<FakeSession>
            get() = fetchSessions.filter { "-history-" in it.id && it.id.startsWith("reac-") }
        val feedFetchSessions: List<FakeSession>
            get() = fetchSessions.filter { it.id.startsWith("feed-") }
        val engagementLiveSessions: List<FakeSession>
            get() = liveSessions.filter { it.id.startsWith("reac-") }

        fun completeInitialFeedHistory() {
            feedFetchSessions.filterNot { it.closed }.forEach { session ->
                val relay = (session.target as? RelayTarget.Single)?.url ?: "relay"
                session.complete(relay)
                // 有限取得は完了とともにリポジトリ側で閉じられる。
                session.closed = true
            }
        }

        override val readableRelayUrls: Flow<Set<String>> = relayUrls

        override fun events(subscriptionId: String): Flow<NostrEvent> = emptyFlow()

        override suspend fun targetRelayUrls(target: RelayTarget): Set<String> {
            val availableRelays = targetRelayUrlsOverride ?: relayUrls.value
            return when (target) {
                RelayTarget.AllEnabled -> availableRelays
                is RelayTarget.Single -> setOf(target.url).intersect(availableRelays)
                is RelayTarget.Explicit -> target.urls.intersect(availableRelays)
            }
        }

        /** null でなければ、付随情報のライブ購読の open をこれが完了するまで止める。 */
        var engagementLiveOpenGate: CompletableDeferred<Unit>? = null
        val openAttempts = mutableListOf<SubscriptionSpec>()

        override suspend fun open(spec: SubscriptionSpec): SubscriptionSession {
            openAttempts += spec
            if (spec.id.startsWith("reac-") && spec.behavior is SubscriptionBehavior.Live) {
                engagementLiveOpenGate?.await()
            }
            return FakeSession(
                spec.id,
                spec.behavior,
                spec.filters,
                spec.target,
                spec.deduplicateEvents,
            ).also(sessions::add)
        }

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

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runNext() {
            check(tasks.isNotEmpty()) { "No queued computation" }
            tasks.removeFirst().run()
        }
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
        pubkey: String = "author",
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
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

    private companion object {
        const val OWN_PUBKEY = "me"
        const val OWN_SESSION_ID = "my-session"
        /** FeedController.ENGAGEMENT_HISTORY_PAGE_THRESHOLD と同じ値。 */
        const val ENGAGEMENT_PAGE_THRESHOLD = 100
    }
}
