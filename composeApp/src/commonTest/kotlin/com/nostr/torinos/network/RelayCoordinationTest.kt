package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RelayCoordinationTest {
    @Test fun firstAcceptanceReturnsWhileOtherDeliveryContinues() = runTest {
        val delivered = mutableListOf<String>()
        val coordinator = RelayPublishCoordinator(this) { _, urls, _, _ ->
            val relay = urls.single()
            delay(if (relay == "fast") 10 else 100)
            delivered += relay
            RelayPublishResult(setOf(relay), emptyMap())
        }
        val result = coordinator.untilFirstSuccess(
            NostrEvent("id", "me", 1, 1, emptyList(), "", "sig"), listOf("fast", "slow", "fast"),
        )
        assertEquals(setOf("fast"), result.succeededRelays)
        assertEquals(listOf("fast"), delivered)
        advanceUntilIdle()
        assertEquals(listOf("fast", "slow"), delivered)
    }

    @Test fun allFailuresAreCombinedEvenWhenObserverThrows() = runTest {
        val coordinator = RelayPublishCoordinator(this) { _, urls, _, _ ->
            delay(1)
            RelayPublishResult(emptySet(), urls.associateWith { "rejected" })
        }
        val result = coordinator.untilFirstSuccess(
            NostrEvent("id", "me", 1, 1, emptyList(), "", "sig"), listOf("a", "b"),
            onRelayResult = { error("observer failed") },
        )
        assertEquals(setOf("a", "b"), result.failedRelays.keys)
    }

    @Test fun sendExceptionFinishesInsteadOfLeavingAwaiterPending() = runTest {
        val coordinator = RelayPublishCoordinator(this) { _, _, _, _ -> error("transport failed") }
        val result = coordinator.untilFirstSuccess(NostrEvent("id", "me", 1, 1, emptyList(), "", ""), listOf("a"))
        assertEquals(mapOf("a" to "transport failed"), result.failedRelays)
    }

    @Test fun rejectedRelayClassificationSurvivesAggregation() = runTest {
        val coordinator = RelayPublishCoordinator(this) { _, urls, _, _ ->
            RelayPublishResult(emptySet(), urls.associateWith { "denied" }, urls.toSet())
        }
        val result = coordinator.untilFirstSuccess(NostrEvent("id", "me", 1, 1, emptyList(), "", ""), listOf("a", "b"))
        assertEquals(setOf("a", "b"), result.rejectedRelays)
    }

    @Test fun cancelledDeliveryScopeDoesNotLeaveAwaiterPending() = runTest {
        val owner = CoroutineScope(SupervisorJob() + coroutineContext.minusKey(Job))
        owner.cancel()
        val coordinator = RelayPublishCoordinator(owner) { _, _, _, _ -> error("must not run") }
        assertFailsWith<CancellationException> {
            coordinator.untilFirstSuccess(NostrEvent("id", "me", 1, 1, emptyList(), "", ""), listOf("a"))
        }
    }

    @Test fun registryRetainsOwnerAndWaitsForAvailableSlot() {
        val registry = SubscriptionRegistry()
        val filter = listOf(NostrFilter(kinds = listOf(1)))
        val first = SubscriptionSessionImpl("same")
        val replacement = SubscriptionSessionImpl("same")
        val record = ActiveSubscriptionRecord(filter, RelayTarget.Single("relay"), SubscriptionBehavior.Live, first)
        registry.register("same", record)
        assertTrue(registry.owns(first))
        assertFalse(registry.owns(replacement))
        assertFailsWith<IllegalStateException> { registry.register("same", record.copy(session = replacement)) }
        fun reconcile() = registry.reconcile("same", record, listOf("relay"), mapOf("relay" to 1L), { 1 }, { _, _, _ -> })
        assertEquals(1, reconcile().size)
        assertTrue(reconcile().isEmpty())
        val waiting = ActiveSubscriptionRecord(filter, RelayTarget.Single("relay"), SubscriptionBehavior.Live)
        registry.register("waiting", waiting)
        fun reconcileWaiting() = registry.reconcile("waiting", waiting, listOf("relay"), mapOf("relay" to 1L), { 1 }, { _, _, _ -> })
        assertTrue(reconcileWaiting().isEmpty())
        registry.records.remove("same")
        assertEquals(1, reconcileWaiting().size)
        first.finish()
        replacement.finish()
    }
}
