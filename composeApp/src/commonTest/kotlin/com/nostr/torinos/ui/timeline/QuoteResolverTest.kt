package com.nostr.torinos.ui.timeline

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuoteResolverTest {
    @Test
    fun returnsTypedResolutionAndAlwaysClosesItsSession(): Unit = runBlocking {
        val event = NostrEvent("found", "author", 1, 1, emptyList(), "content", "sig")
        var closed = false
        val session = object : SubscriptionSession {
            override val id = "quote"
            override val signals: Flow<SubscriptionSignal> = flowOf(
                SubscriptionSignal.Event("relay", event, isLive = false),
                SubscriptionSignal.FetchCompleted(mapOf("relay" to RelayOutcome.Eose), timedOut = false),
            )
            override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
            override suspend fun close() { closed = true }
        }
        val resolver = QuoteResolver("test") { session }

        val result = resolver.resolve(setOf("found", "missing"), listOf(1))

        assertEquals(event, result.events["found"])
        assertEquals(setOf("missing"), result.missingIds)
        assertTrue(closed)
    }
}
