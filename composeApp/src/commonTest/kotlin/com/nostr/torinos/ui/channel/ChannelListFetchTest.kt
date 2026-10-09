package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChannelListFetchTest {
    private val spec = SubscriptionSpec(
        id = "test", filters = listOf(NostrFilter(kinds = listOf(40), limit = 50)),
        behavior = SubscriptionBehavior.Fetch(100),
    )

    @Test
    fun immediateEventsAndCompletionAreConsumedAndClosed() = runTest {
        val event = NostrEvent("id", "author", 10, 40, emptyList(), "{}", "")
        val session = FakeSession(flowOf(
            SubscriptionSignal.Event("relay", event, false),
            SubscriptionSignal.FetchCompleted(mapOf("relay" to RelayOutcome.Eose), false),
        ))
        val received = mutableListOf<NostrEvent>()
        assertTrue(fetchChannelEvents(spec, { session }) { received += it })
        assertEquals(listOf(event), received)
        assertTrue(session.closed)
    }

    @Test
    fun unresponsiveRelayTimesOutAndCloses() = runTest {
        val session = FakeSession(flow { awaitCancellation() })
        assertFalse(fetchChannelEvents(spec, { session }) {})
        assertEquals(100, testScheduler.currentTime)
        assertTrue(session.closed)
    }

    @Test
    fun refusalDoesNotCountAsSuccessfulEndOfPage() = runTest {
        val session = FakeSession(flowOf(SubscriptionSignal.FetchCompleted(
            mapOf("relay" to RelayOutcome.Closed("restricted")), false,
        )))
        assertFalse(fetchChannelEvents(spec, { session }) {})
        assertTrue(session.closed)
    }

    @Test
    fun cancellationClosesSubscription() = runTest {
        val session = FakeSession(flow { awaitCancellation() })
        val job = launch { fetchChannelEvents(spec, { session }) {} }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(session.closed)
    }

    @Test
    fun settleModeFinishesShortlyAfterFirstEoseWhenAnotherRelayHangs() = runTest {
        val event = NostrEvent("id", "author", 10, 40, emptyList(), "{}", "")
        val session = FakeSession(flow {
            emit(SubscriptionSignal.Event("alive", event, false))
            emit(SubscriptionSignal.Eose("alive"))
            awaitCancellation() // dead relay never answers
        })
        val longSpec = spec.copy(behavior = SubscriptionBehavior.Fetch(10_000))
        val received = mutableListOf<NostrEvent>()
        assertTrue(fetchChannelEvents(longSpec, { session }, settleAfterEoseMillis = 1_500) { received += it })
        assertEquals(1_500, testScheduler.currentTime)
        assertEquals(listOf(event), received)
        assertTrue(session.closed)
    }

    @Test
    fun settleWaitRestartsWhenAnotherRelayFinishes() = runTest {
        val early = NostrEvent("early", "author", 10, 40, emptyList(), "{}", "")
        val late = NostrEvent("late", "author", 11, 40, emptyList(), "{}", "")
        val session = FakeSession(flow {
            emit(SubscriptionSignal.Event("fast", early, false))
            emit(SubscriptionSignal.Eose("fast"))
            delay(1_000)
            emit(SubscriptionSignal.Eose("medium"))
            // 遅れて接続したリレー: 最初の EOSE から1.5秒より後に届く。
            delay(1_000)
            emit(SubscriptionSignal.Event("late", late, false))
            emit(SubscriptionSignal.Eose("late"))
            awaitCancellation()
        })
        val longSpec = spec.copy(behavior = SubscriptionBehavior.Fetch(10_000))
        val received = mutableListOf<NostrEvent>()
        assertTrue(fetchChannelEvents(longSpec, { session }, settleAfterEoseMillis = 1_500) { received += it })
        assertEquals(listOf(early, late), received)
        assertEquals(3_500, testScheduler.currentTime)
    }

    @Test
    fun settleModeSucceedsWhenTheOverallTimeoutComesAfterAnEose() = runTest {
        val session = FakeSession(flow {
            emit(SubscriptionSignal.Eose("a"))
            repeat(20) {
                delay(1_000)
                emit(SubscriptionSignal.Eose("r$it"))
            }
            awaitCancellation()
        })
        val longSpec = spec.copy(behavior = SubscriptionBehavior.Fetch(5_000))
        assertTrue(fetchChannelEvents(longSpec, { session }, settleAfterEoseMillis = 1_500) {})
        assertEquals(5_000, testScheduler.currentTime)
        assertTrue(session.closed)
    }

    @Test
    fun settleModeAcceptsPartialCompletionButNotTotalFailure() = runTest {
        val partial = FakeSession(flowOf(SubscriptionSignal.FetchCompleted(
            mapOf("a" to RelayOutcome.Eose, "b" to RelayOutcome.Closed("restricted")), false,
        )))
        assertTrue(fetchChannelEvents(spec, { partial }, settleAfterEoseMillis = 50) {})
        val none = FakeSession(flowOf(SubscriptionSignal.FetchCompleted(
            mapOf("a" to RelayOutcome.TimedOut, "b" to RelayOutcome.Closed("restricted")), true,
        )))
        assertFalse(fetchChannelEvents(spec, { none }, settleAfterEoseMillis = 50) {})
    }

    @Test
    fun settleModeWithNoEoseStillTimesOut() = runTest {
        val session = FakeSession(flow { awaitCancellation() })
        assertFalse(fetchChannelEvents(spec, { session }, settleAfterEoseMillis = 50) {})
        assertEquals(100, testScheduler.currentTime)
    }

    private class FakeSession(override val signals: Flow<SubscriptionSignal>) : SubscriptionSession {
        override val id = "test"
        var closed = false
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() { closed = true }
    }
}
