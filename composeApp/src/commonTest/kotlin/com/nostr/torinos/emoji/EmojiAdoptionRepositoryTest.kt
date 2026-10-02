package com.nostr.torinos.emoji

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiAdoptionRepositoryTest {
    private val own = "f".repeat(64)
    private val user = "a".repeat(64)
    private val address = EmojiSetAddress("c".repeat(64), "cats")
    private val event = NostrEvent("id", user, 1, 10030, listOf(listOf("a", address.value)), "", "sig")

    @Test
    fun excludesSelfAndLimitsRequestsToSelectedRelayWithValidSubscriptionId() = runTest {
        val requests = mutableListOf<SubscriptionSpec>()
        val transport = EmojiDiscoveryTransport(open = { spec ->
            requests += spec
            response(spec, event)
        }, validate = { true })
        val repo = EmojiAdoptionRepository(own, "$own-123", this, transport, StandardTestDispatcher(testScheduler))
        repo.start("wss://one.example", setOf(own, user))
        val state = repo.state.first { !it.isLoading }
        assertEquals(setOf(user), state.follows)
        assertEquals(mapOf(address to 1), state.counts)
        assertEquals(1, requests.size)
        assertEquals(listOf(user), requests.single().filters.single().authors)
        assertTrue(requests.single().id.length <= 64)
        assertEquals(RelayTarget.Single("wss://one.example"), requests.single().target)
        repo.close()
    }

    @Test
    fun relayChangeClosesOldFetchAndDoesNotCarryOverOldCounts() = runTest {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val transport = EmojiDiscoveryTransport(open = { spec ->
            if (spec.target == RelayTarget.Single("wss://old.example")) object : SubscriptionSession {
                override val id = spec.id
                override val signals = flow<SubscriptionSignal> { started.complete(Unit); awaitCancellation() }
                override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
                override suspend fun close() { closed.complete(Unit) }
            } else response(spec, null)
        }, validate = { true })
        val repo = EmojiAdoptionRepository(own, "$own-1", this, transport, StandardTestDispatcher(testScheduler))
        repo.start("wss://old.example", setOf(user))
        started.await()
        repo.start("wss://new.example", setOf(user))
        val state = repo.state.first { !it.isLoading }
        closed.await()
        assertEquals("wss://new.example", state.relayUrl)
        assertTrue(state.counts.isEmpty())
        assertFalse(state.isPartial)
        repo.close()
    }

    @Test
    fun fetchContinuesUntilLastScreenReleases() = runTest {
        val started = CompletableDeferred<Unit>()
        val transport = EmojiDiscoveryTransport(open = { spec ->
            object : SubscriptionSession {
                override val id = spec.id
                override val signals = flow<SubscriptionSignal> { started.complete(Unit); awaitCancellation() }
                override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
                override suspend fun close() = Unit
            }
        }, validate = { true })
        val repo = EmojiAdoptionRepository(own, "$own-2", this, transport, StandardTestDispatcher(testScheduler))
        val releaseCenter = repo.acquire()
        val releasePanel = repo.acquire()
        repo.start("wss://one.example", setOf(user))
        started.await()

        releasePanel()
        releasePanel()
        assertTrue(repo.state.value.isLoading)
        assertFalse(repo.state.value.wasStopped)

        releaseCenter()
        assertFalse(repo.state.value.isLoading)
        assertTrue(repo.state.value.wasStopped)
        repo.close()
    }

    private fun response(spec: SubscriptionSpec, event: NostrEvent?) = object : SubscriptionSession {
        override val id = spec.id
        override val signals = flow {
            val relay = (spec.target as RelayTarget.Single).url
            if (event != null) emit(SubscriptionSignal.Event(relay, event, false))
            emit(SubscriptionSignal.FetchCompleted(mapOf(relay to RelayOutcome.Eose), false))
        }
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() = Unit
    }
}
