package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.matches
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FiniteRangeFetcherTest {
    private val relay = "range-relay"
    private val spec = SubscriptionSpec("range", listOf(NostrFilter(kinds = listOf(3), limit = 100)),
        behavior = SubscriptionBehavior.Fetch(1_000))

    private fun open(events: List<NostrEvent>, cap: Int, requests: MutableList<SubscriptionSpec>): suspend (SubscriptionSpec) -> SubscriptionSession = { request ->
        requests += request
        object : SubscriptionSession {
            override val id = request.id
            override val expectedRelayUrls = setOf(relay)
            override val signals = flow {
                events.filter { request.filters.single().matches(it) }.take(minOf(cap, request.filters.single().limit ?: cap)).forEach {
                    emit(SubscriptionSignal.Event(relay, it, false))
                }
                emit(SubscriptionSignal.FetchCompleted(mapOf(relay to RelayOutcome.Eose), false))
            }
            override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
            override suspend fun close() = Unit
        }
    }

    @Test fun unknownShortPageRequiresBoundaryAndEndEvidence() = runTest {
        val events = listOf(2L, 1L).map { NostrEvent("id-$it", "author", it, 3, emptyList(), "", "") }
        val requests = mutableListOf<SubscriptionSpec>()
        val result = fetchFiniteRange(spec, open(events, 2, requests))
        assertTrue(result.rangeComplete)
        assertEquals(setOf("id-1", "id-2"), result.eventsById.keys)
        assertEquals(listOf(null, 1L, 0L), requests.map { it.filters.single().until })
    }

    @Test fun hiddenCapAndPageBudgetNeverAdvanceCoverage() = runTest {
        val events = (5L downTo 1L).map { NostrEvent("id-$it", "author", it, 3, emptyList(), "", "") }
        val requests = mutableListOf<SubscriptionSpec>()
        val result = fetchFiniteRange(spec, open(events, 2, requests))
        assertFalse(result.rangeComplete)
        assertEquals(FetchStopReason.PageBudgetReached, result.stopReason)
        assertEquals(3, requests.size)
        assertEquals(listOf(null, 4L, 3L), requests.map { it.filters.single().until })
    }

    @Test fun unresolvedSameSecondStopsWithoutSkippingIt() = runTest {
        val events = (1..3).map { NostrEvent("id-$it", "author", 10, 3, emptyList(), "", "") }
        val requests = mutableListOf<SubscriptionSpec>()
        val result = fetchFiniteRange(spec, open(events, 1, requests))
        assertFalse(result.rangeComplete)
        assertEquals(3, requests.size)
        assertTrue(requests.drop(1).all { it.filters.single().until == 10L })
    }
}
