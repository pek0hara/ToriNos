package com.nostr.torinos.ui.thread

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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PostRelayResolverTest {
    private val post = NostrEvent("post", "author", 1, 1, emptyList(), "text", "sig")

    @Test fun probesWriteRelaysButOnlyReportsActualSources(): Unit = runBlocking {
        val older = post.copy(id = "older", kind = 10002, tags = listOf(listOf("r", "wss://old")))
        val latest = older.copy(id = "newer", createdAt = 2, tags = listOf(
            listOf("r", "wss://read", "read"),
            listOf("r", "wss://write", "write"),
            listOf("r", "wss://both"),
            listOf("r", "https://invalid"),
        ))
        val specs = mutableListOf<SubscriptionSpec>()
        val sessions = mutableListOf<FakeSession>()
        val snapshots = mutableListOf<Set<String>>()
        val resolver = PostRelayResolver(
            opener = { spec ->
                specs += spec
                FakeSession(if (specs.size == 1) flowOf(
                    received("wss://viewer", latest), received("wss://viewer", older), completed(),
                ) else flowOf(
                    received("cache://events", post),
                    received("wss://write", post),
                    received("wss://viewer", post),
                    received("wss://both", post.copy(id = "other")),
                    completed(),
                )).also { sessions += it }
            },
            observedRelays = { setOf("wss://previous") },
            viewerRelays = { setOf("wss://viewer") },
        )
        assertTrue(resolver.resolve(post, snapshots::add))
        assertEquals(RelayTarget.AllEnabled, specs[0].target)
        assertEquals(RelayTarget.Explicit(setOf("wss://viewer", "wss://write", "wss://both")), specs[1].target)
        assertFalse(specs[1].deduplicateEvents)
        assertEquals(setOf("wss://previous", "wss://write", "wss://viewer"), snapshots.last())
        assertTrue(sessions.all { it.closed })
    }

    @Test fun preservesFoundRelaysWhenProbeTimesOut(): Unit = runBlocking {
        var count = 0
        var found = emptySet<String>()
        val resolver = PostRelayResolver(
            opener = {
                count++
                FakeSession(if (count == 1) flowOf(completed()) else flowOf(
                    received("wss://viewer", post),
                    SubscriptionSignal.FetchCompleted(mapOf("wss://slow" to RelayOutcome.TimedOut), true),
                ))
            },
            observedRelays = { emptySet() },
            viewerRelays = { setOf("wss://viewer", "wss://slow") },
        )
        assertFalse(resolver.resolve(post) { found = it })
        assertEquals(setOf("wss://viewer"), found)
    }

    @Test fun closesSubscriptionOnCancellation(): Unit = runBlocking {
        val session = FakeSession(flow { throw CancellationException("cancelled") })
        val resolver = PostRelayResolver(opener = { session }, observedRelays = { emptySet() })
        assertFailsWith<CancellationException> { resolver.resolve(post) {} }
        assertTrue(session.closed)
    }

    private fun received(relay: String, event: NostrEvent) = SubscriptionSignal.Event(relay, event, false)
    private fun completed() = SubscriptionSignal.FetchCompleted(mapOf("wss://viewer" to RelayOutcome.Eose), false)

    private class FakeSession(override val signals: Flow<SubscriptionSignal>) : SubscriptionSession {
        override val id = "test"
        var closed = false
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() { closed = true }
    }
}
