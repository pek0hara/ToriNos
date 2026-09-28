package com.nostr.torinos.ui.timeline

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuoteResolverTest {
    @Test
    fun reportsEventBeforeFetchCompletes(): Unit = runBlocking {
        val event = NostrEvent("found", "author", 1, 1111, emptyList(), "content", "sig")
        val eventCollected = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        val reported = mutableListOf<NostrEvent>()
        val session = object : SubscriptionSession {
            override val id = "quote"
            override val signals: Flow<SubscriptionSignal> = flow {
                emit(SubscriptionSignal.Event("relay", event, isLive = false))
                eventCollected.complete(Unit)
                allowCompletion.await()
                emit(SubscriptionSignal.FetchCompleted(mapOf("relay" to RelayOutcome.Eose), timedOut = false))
            }
            override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
            override suspend fun close() = Unit
        }
        val resolver = QuoteResolver("test") { session }

        val resolution = async {
            resolver.resolve(setOf("found"), listOf(1, 1111), onEvent = reported::add)
        }

        eventCollected.await()
        assertEquals(listOf(event), reported)
        assertTrue(resolution.isActive)

        allowCompletion.complete(Unit)
        assertEquals(event, resolution.await().events["found"])
    }

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
