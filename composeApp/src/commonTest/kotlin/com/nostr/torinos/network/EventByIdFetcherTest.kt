package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.buildReqMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal fun targetEvent(index: Int, kind: Int = 1) = NostrEvent(
    index.toString(16).padStart(64, '0'), "a".repeat(64), 100, kind, emptyList(), "body", "b".repeat(128),
)

internal class FakeTargetSession : SubscriptionSession {
    override val id = "fake"
    val channel = Channel<SubscriptionSignal>(Channel.UNLIMITED)
    override val signals = channel.receiveAsFlow()
    var closed = false
    override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = error("finite")
    override suspend fun close() { closed = true; channel.close() }
    fun event(event: NostrEvent) {
        channel.trySend(SubscriptionSignal.Event("relay", event, false)).getOrThrow()
    }
    fun completed(outcomes: Map<String, RelayOutcome>, timedOut: Boolean = false) {
        channel.trySend(SubscriptionSignal.FetchCompleted(outcomes, timedOut)).getOrThrow()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EventByIdFetcherTest {
    @Test fun acceptsAllKindsAndClosesAsSoonAsEveryIdArrives() = runTest {
        val events = listOf(1, 42, 30023, 30311, 1311, 30315, 99999).mapIndexed { i, kind -> targetEvent(i, kind) }
        val session = FakeTargetSession()
        session.event(targetEvent(999)) // unsolicited
        events.forEach { session.event(it); session.event(it) }
        var spec: SubscriptionSpec? = null
        val fetcher = EventByIdFetcher({ spec = it; session }, { true })
        val received = mutableListOf<NostrEvent>()
        fetcher.fetch(events.map { it.id }.toSet(), received::add)
        assertEquals(events, received)
        assertTrue(session.closed)
        val actualSpec = requireNotNull(spec)
        val request = buildReqMessage(actualSpec.id, actualSpec.filters)
        assertFalse(request.contains("kinds"))
        assertTrue(request.contains("ids"))
        assertIs<SubscriptionBehavior.Fetch>(actualSpec.behavior)
        assertFalse(actualSpec.deduplicateEvents)
    }

    @Test fun emptyIdsDoNotOpenSubscription() = runTest {
        EventByIdFetcher({ error("must not open") }, { true }).fetch(emptySet()) { error("event") }
    }

    @Test fun waitsForAllRelaysAndRejectsInvalidEvents() = runTest {
        val event = targetEvent(1, 42)
        val session = FakeTargetSession()
        val received = mutableListOf<NostrEvent>()
        val job = launch {
            EventByIdFetcher({ session }, { it.content != "invalid" }).fetch(setOf(event.id), received::add)
        }
        session.channel.send(SubscriptionSignal.Eose("fast"))
        session.event(event.copy(content = "invalid"))
        runCurrent()
        assertTrue(job.isActive)
        assertTrue(received.isEmpty())
        session.event(event)
        runCurrent()
        assertEquals(listOf(event), received)
        assertTrue(session.closed)
    }

    @Test fun distinguishesMissingNoRelayPartialFailureAndInvalidResponse() = runTest {
        suspend fun result(outcomes: Map<String, RelayOutcome>, invalid: Boolean = false, timeout: Boolean = false): TargetLoadState {
            val session = FakeTargetSession()
            if (invalid) session.event(targetEvent(1))
            session.completed(outcomes, timeout)
            return EventByIdFetcher({ session }, { false }).fetch(setOf(targetEvent(1).id)) { error("invalid") }.missingState
        }
        assertEquals(TargetLoadState.NotFoundInQueriedRelays, result(mapOf("r" to RelayOutcome.Eose)))
        assertEquals(TargetLoadState.Unavailable(TargetFetchFailure.NoRelay), result(emptyMap()))
        listOf(RelayOutcome.Closed("denied"), RelayOutcome.Unavailable("offline"), RelayOutcome.TimedOut).forEach {
            assertEquals(TargetLoadState.Unavailable(TargetFetchFailure.Incomplete), result(mapOf("a" to RelayOutcome.Eose, "b" to it)))
        }
        assertEquals(TargetLoadState.Unavailable(TargetFetchFailure.Incomplete), result(mapOf("r" to RelayOutcome.Eose), timeout = true))
        assertEquals(TargetLoadState.Unavailable(TargetFetchFailure.InvalidResponse), result(mapOf("r" to RelayOutcome.Eose), invalid = true))
    }

    @Test fun partialSuccessSurvivesTimeoutAndCancellationClosesSession() = runTest {
        val session = FakeTargetSession()
        val received = mutableListOf<NostrEvent>()
        session.event(targetEvent(1))
        session.completed(mapOf("r" to RelayOutcome.TimedOut), true)
        val result = EventByIdFetcher({ session }, { true }).fetch(setOf(targetEvent(1).id, targetEvent(2).id), received::add)
        assertEquals(listOf(targetEvent(1)), received)
        assertIs<TargetLoadState.Unavailable>(result.missingState)
        val waiting = FakeTargetSession()
        val job = launch { EventByIdFetcher({ waiting }, { true }).fetch(setOf(targetEvent(1).id)) {} }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(waiting.closed)
    }
}
