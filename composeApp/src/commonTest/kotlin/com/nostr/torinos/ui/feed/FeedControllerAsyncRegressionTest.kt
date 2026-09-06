package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedControllerAsyncRegressionTest {
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

        historyB.event(event("new", 20), relay = "relay-b")
        historyB.complete("relay-b")
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
        nextPage.event(duplicate, relay = "history-relay")
        nextPage.complete("history-relay")
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

    private class FakeGateway : FeedSubscriptionGateway {
        val sessions = mutableListOf<FakeSession>()
        val fetchSessions: List<FakeSession>
            get() = sessions.filter { it.behavior is SubscriptionBehavior.Fetch }
        val liveSessions: List<FakeSession>
            get() = sessions.filter { it.behavior is SubscriptionBehavior.Live }

        override fun events(subscriptionId: String): Flow<NostrEvent> = emptyFlow()

        override suspend fun open(spec: SubscriptionSpec): SubscriptionSession =
            FakeSession(spec.id, spec.behavior).also(sessions::add)

        override suspend fun subscribe(
            subscriptionId: String,
            filter: NostrFilter,
            target: RelayTarget,
        ): Set<String> = emptySet()

        override fun close(subscriptionId: String) = Unit
    }

    private class FakeSession(
        override val id: String,
        val behavior: SubscriptionBehavior,
    ) : SubscriptionSession {
        private val mutableSignals = MutableSharedFlow<SubscriptionSignal>(extraBufferCapacity = 32)
        override val signals: Flow<SubscriptionSignal> = mutableSignals
        var closed = false

        fun event(event: NostrEvent, relay: String = "relay") {
            assertTrue(mutableSignals.tryEmit(SubscriptionSignal.Event(relay, event, isLive = false)))
        }

        fun complete(relay: String = "relay") {
            assertTrue(
                mutableSignals.tryEmit(
                    SubscriptionSignal.FetchCompleted(
                        outcomes = mapOf(relay to RelayOutcome.Eose),
                        timedOut = false,
                    ),
                ),
            )
        }

        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit

        override suspend fun close() {
            closed = true
        }
    }

    private fun event(id: String, createdAt: Long) = NostrEvent(
        id = id,
        pubkey = "author",
        createdAt = createdAt,
        kind = 1,
        tags = emptyList(),
        content = id,
        sig = "signature",
    )
}
