package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.*
import kotlinx.coroutines.awaitCancellation
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

    private class FakeSession(override val signals: Flow<SubscriptionSignal>) : SubscriptionSession {
        override val id = "test"
        var closed = false
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() { closed = true }
    }
}
