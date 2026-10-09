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
import kotlinx.coroutines.test.TestScope
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

    @Test
    fun resumeAfterStopFetchesOnlyBatchesThatDidNotFinish() = runTest {
        val users = (0 until 100).map { it.toString().padStart(64, '0') }
        val requests = mutableListOf<SubscriptionSpec>()
        var hangSecondBatch = true
        val secondStarted = CompletableDeferred<Unit>()
        val transport = EmojiDiscoveryTransport(open = { spec ->
            requests += spec
            if (hangSecondBatch && "50".padStart(64, '0') in spec.filters.single().authors!!) {
                hanging(spec) { secondStarted.complete(Unit) }
            } else response(spec, null)
        }, validate = { true })
        val repo = EmojiAdoptionRepository(own, "$own-3", this, transport, StandardTestDispatcher(testScheduler))
        repo.start("wss://one.example", users.toSet())
        secondStarted.await()
        testScheduler.runCurrent()
        repo.stop()

        hangSecondBatch = false
        repo.start("wss://one.example", users.toSet())
        repo.state.first { !it.isLoading }

        assertEquals(3, requests.size)
        assertEquals(users.drop(50), requests.last().filters.single().authors)
        repo.close()
    }

    @Test
    fun addedFollowsAreFetchedWithoutRefetchingKnownOnes() = runTest {
        val requests = mutableListOf<SubscriptionSpec>()
        val repo = repository(requests, "$own-4")
        repo.start("wss://one.example", setOf(user))
        repo.state.first { !it.isLoading }

        val added = "b".repeat(64)
        repo.start("wss://one.example", setOf(user, added))
        val state = repo.state.first { !it.isLoading }

        assertEquals(listOf(listOf(user), listOf(added)), requests.map { it.filters.single().authors })
        assertEquals(setOf(user, added), state.follows)
        assertEquals(mapOf(address to 1), state.counts)
        repo.close()
    }

    @Test
    fun removedFollowsAreDroppedWithoutAnyRequest() = runTest {
        val requests = mutableListOf<SubscriptionSpec>()
        val other = "b".repeat(64)
        val repo = repository(requests, "$own-5")
        repo.start("wss://one.example", setOf(user, other))
        repo.state.first { !it.isLoading }

        repo.start("wss://one.example", setOf(other))

        assertEquals(1, requests.size)
        assertFalse(repo.state.value.isLoading)
        assertEquals(setOf(other), repo.state.value.follows)
        assertTrue(repo.state.value.counts.isEmpty())
        repo.close()
    }

    @Test
    fun refetchesEveryoneWhenStaleOrForced() = runTest {
        val requests = mutableListOf<SubscriptionSpec>()
        var now = 0L
        val repo = repository(requests, "$own-6") { now }
        repo.start("wss://one.example", setOf(user))
        repo.state.first { !it.isLoading }

        now = 299_000
        repo.start("wss://one.example", setOf(user))
        assertEquals(1, requests.size, "5分以内は取り直さない")

        repo.start("wss://one.example", setOf(user), force = true)
        repo.state.first { !it.isLoading }
        assertEquals(2, requests.size)

        now += 300_000
        repo.start("wss://one.example", setOf(user))
        val state = repo.state.first { !it.isLoading }
        assertEquals(3, requests.size)
        assertEquals(mapOf(address to 1), state.counts)
        repo.close()
    }

    private fun TestScope.repository(
        requests: MutableList<SubscriptionSpec>,
        sessionId: String,
        now: () -> Long = { 0L },
    ): EmojiAdoptionRepository {
        val transport = EmojiDiscoveryTransport(open = { spec ->
            requests += spec
            response(spec, event.takeIf { user in spec.filters.single().authors!! })
        }, validate = { true })
        return EmojiAdoptionRepository(own, sessionId, this, transport, StandardTestDispatcher(testScheduler), now)
    }

    private fun hanging(spec: SubscriptionSpec, onStart: () -> Unit) = object : SubscriptionSession {
        override val id = spec.id
        override val signals = flow<SubscriptionSignal> { onStart(); awaitCancellation() }
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() = Unit
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
