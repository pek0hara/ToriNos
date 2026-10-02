package com.nostr.torinos.emoji

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiDiscoveryTransportTest {
    private val relay = "wss://one.example"
    private val author = "a".repeat(64)
    private val event = NostrEvent("id", author, 1, 10030, emptyList(), "", "sig")
    private val filter = NostrFilter(kinds = listOf(10030), authors = listOf(author), limit = 1)

    @Test
    fun cacheAndOtherRelayDoNotCountOrSuppressTheRealRelayResponse() = runTest {
        var captured: SubscriptionSpec? = null
        val session = fakeSession(listOf(
            SubscriptionSignal.Event("cache", event, false),
            SubscriptionSignal.Event("wss://other.example", event, false),
            SubscriptionSignal.Event(relay, event, false),
            SubscriptionSignal.Event(relay, event, false),
            SubscriptionSignal.Event(relay, event.copy(id = "bad-author", pubkey = "b".repeat(64)), false),
            SubscriptionSignal.Event(relay, event.copy(id = "bad-kind", kind = 1), false),
            SubscriptionSignal.Event(relay, event.copy(id = "bad-signature"), false),
            SubscriptionSignal.FetchCompleted(mapOf(relay to RelayOutcome.Eose), false),
        ))
        val transport = EmojiDiscoveryTransport(open = { captured = it; session }, validate = { it.id != "bad-signature" })
        val result = transport.fetch("request", relay, listOf(filter))
        assertEquals(RelayTarget.Single(relay), captured?.target)
        assertEquals(false, captured?.deduplicateEvents)
        assertEquals(listOf(event), result.events)
        assertTrue(result.complete)
        assertTrue(session.closed)
    }

    @Test
    fun timeoutPreservesPartialEventsAndClosesSession() = runTest {
        val session = fakeSession(listOf(
            SubscriptionSignal.Event(relay, event, false),
            SubscriptionSignal.FetchCompleted(mapOf(relay to RelayOutcome.TimedOut), true),
        ))
        val result = EmojiDiscoveryTransport(open = { session }, validate = { true })
            .fetch("request", relay, listOf(filter))
        assertEquals(listOf(event), result.events)
        assertFalse(result.complete)
        assertTrue(session.closed)
    }

    @Test
    fun cancellationIsNotReportedAsACompletedEmptyList() = runTest {
        val session = fakeSession(emptyList(), cancel = true)
        var cancelled = false
        try {
            EmojiDiscoveryTransport(open = { session }, validate = { true }).fetch("request", relay, listOf(filter))
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(session.closed)
    }

    @Test
    fun exactAddressFiltersRejectCrossProductMatches() {
        val setFilter = NostrFilter(kinds = listOf(30030), authors = listOf(author), dTags = listOf("cats"))
        assertTrue(setFilter.acceptsEmojiDiscoveryEvent(event.copy(kind = 30030, tags = listOf(listOf("d", "cats")))))
        assertFalse(setFilter.acceptsEmojiDiscoveryEvent(event.copy(kind = 30030, tags = listOf(listOf("d", "dogs")))))
    }

    private fun fakeSession(signals: List<SubscriptionSignal>, cancel: Boolean = false) = object : SubscriptionSession {
        var closed = false
        override val id = "request"
        override val signals: Flow<SubscriptionSignal> = flow {
            signals.forEach { emit(it) }
            if (cancel) throw CancellationException("left screen")
        }
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() { closed = true }
    }
}
