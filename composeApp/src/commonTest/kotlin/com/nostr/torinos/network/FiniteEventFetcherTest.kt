package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FiniteEventFetcherTest {
    private val spec = SubscriptionSpec("fetch", listOf(NostrFilter(kinds = listOf(1), limit = 10)),
        behavior = SubscriptionBehavior.Fetch(1_000))
    private val event = NostrEvent("id", "author", 1, 1, emptyList(), "note", "")

    @Test fun emptyFastRelayDoesNotHideSlowRelay() = runTest {
        val session = Fake(setOf("fast", "slow"), flow {
            emit(SubscriptionSignal.Eose("fast"))
            delay(100)
            emit(SubscriptionSignal.Event("slow", event, false))
            emit(SubscriptionSignal.FetchCompleted(mapOf("fast" to RelayOutcome.Eose, "slow" to RelayOutcome.Eose), false))
        })
        val result = fetchFiniteEvents(spec, { session })
        assertEquals(listOf(event), result.events)
        assertTrue(result.allAnswered)
        assertTrue(session.closed)
    }

    @Test fun duplicateKeepsBothSourcesAndNetworkOnlySpec() = runTest {
        val session = Fake(setOf("a", "b"), flowOf(
            SubscriptionSignal.Event("a", event, false), SubscriptionSignal.Event("b", event, false),
            SubscriptionSignal.FetchCompleted(mapOf("a" to RelayOutcome.Eose, "b" to RelayOutcome.Eose), false),
        ))
        val result = fetchFiniteEvents(spec, { opened ->
            assertFalse(opened.replayCachedEvents)
            assertFalse(opened.deduplicateEvents)
            session
        })
        assertEquals(1, result.events.size)
        assertEquals(setOf("a", "b"), result.sourceRelays[event.id])
        assertEquals(setOf(event.id), result.relays.getValue("a").receivedIds)
    }

    @Test fun refusalAndMissingCompletionNeverConfirmCoverage() = runTest {
        val refusal = Fake(setOf("a"), flowOf(SubscriptionSignal.FetchCompleted(
            mapOf("a" to RelayOutcome.Closed("restricted")), false)))
        assertFalse(fetchFiniteEvents(spec, { refusal }).allAnswered)
        val interrupted = Fake(setOf("a"), flowOf(SubscriptionSignal.Eose("a")))
        val result = fetchFiniteEvents(spec, { interrupted })
        assertEquals(FetchStopReason.UnexpectedEnd, result.stopReason)
        assertFalse(result.allAnswered)
    }

    @Test fun targetlessFetchIsNotSuccessful() = runTest {
        val session = Fake(emptySet(), flowOf(SubscriptionSignal.FetchCompleted(emptyMap(), false)))
        assertEquals(FetchStopReason.NoTargets, fetchFiniteEvents(spec, { session }).stopReason)
    }

    @Test fun limitReachedIsNotRangeCoverage() = runTest {
        val session = Fake(setOf("a"), flowOf(SubscriptionSignal.Event("a", event, false),
            SubscriptionSignal.FetchCompleted(mapOf("a" to RelayOutcome.Eose), false)))
        val result = fetchFiniteEvents(spec.copy(filters = listOf(NostrFilter(limit = 1))), { session })
        assertTrue(result.allAnswered)
        assertFalse(result.rangeComplete)
    }

    @Test fun settleEndsPartialPageWithoutAdvancingMissingRelay() = runTest {
        val channel = Channel<SubscriptionSignal>(Channel.UNLIMITED)
        val session = object : SubscriptionSession {
            override val id = "settle"
            override val expectedRelayUrls = setOf("a", "b")
            override val signals = channel.receiveAsFlow()
            override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
            override suspend fun close() { channel.close() }
            override suspend fun finishPendingRelays() {
                channel.send(SubscriptionSignal.FetchCompleted(mapOf("a" to RelayOutcome.Eose, "b" to RelayOutcome.TimedOut), true))
            }
        }
        channel.send(SubscriptionSignal.Eose("a"))
        val result = fetchFiniteEvents(spec, { session }, settleAfterEoseMillis = 100)
        assertEquals(100, testScheduler.currentTime)
        assertEquals(FetchStopReason.SettleExpired, result.stopReason)
        assertFalse(result.allAnswered)
        assertEquals(RelayOutcome.TimedOut, result.relays.getValue("b").outcome)
    }

    @Test fun cancellationAndDeadlineCloseOwnedSession() = runTest {
        val session = Fake(setOf("a"), flow { awaitCancellation() })
        val job = launch { fetchFiniteEvents(spec, { session }) }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(session.closed)
        val other = Fake(setOf("a"), flow { awaitCancellation() })
        val result = fetchFiniteEvents(spec, { other })
        assertEquals(FetchStopReason.DeadlineExpired, result.stopReason)
        assertTrue(other.closed)
    }

    private class Fake(override val expectedRelayUrls: Set<String>, override val signals: Flow<SubscriptionSignal>) : SubscriptionSession {
        override val id = "fake"
        var closed = false
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() { closed = true }
    }
}
