package com.nostr.torinos.network

import com.nostr.torinos.model.NostrFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SubscriptionStateMachineTest {
    private val oldFilters = listOf(NostrFilter(kinds = listOf(1), since = 100L))
    private val newFilters = listOf(NostrFilter(kinds = listOf(1), since = 200L))

    @Test
    fun initialSubscriptionOpensRequest() {
        val result = SubscriptionStateMachine.reconcile(null, oldFilters, 1L)

        assertEquals(RelaySubscriptionPhase.Sent, result.state?.phase)
        assertEquals(oldFilters, assertIs<SubscriptionCommandDecision.Open>(result.command).filters)
    }

    @Test
    fun updateWaitsUntilEose() {
        val sent = SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!
        val pending = SubscriptionStateMachine.reconcile(sent, newFilters, 1L)

        assertNull(pending.command)
        assertEquals(oldFilters, pending.state?.sentFilters)

        val live = SubscriptionStateMachine.onEose(pending.state!!)
        val updated = SubscriptionStateMachine.reconcile(live, newFilters, 1L)

        assertEquals(newFilters, assertIs<SubscriptionCommandDecision.Open>(updated.command).filters)
    }

    @Test
    fun targetRemovalClosesSentSubscription() {
        val sent = SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!
        val result = SubscriptionStateMachine.reconcile(sent, null, 1L)

        assertIs<SubscriptionCommandDecision.Close>(result.command)
        assertEquals(RelaySubscriptionPhase.Closing, result.state?.phase)
    }

    @Test
    fun reconnectResendsLatestDesiredFilters() {
        val sent = SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!
        val disconnected = SubscriptionStateMachine.onDisconnected(sent, 2L)
        val result = SubscriptionStateMachine.reconcile(disconnected, newFilters, 2L)

        assertEquals(newFilters, assertIs<SubscriptionCommandDecision.Open>(result.command).filters)
        assertEquals(2L, result.state?.connectionGeneration)
    }

    @Test
    fun repeatedStructuralRefusalSuppressesSameFilter() {
        var state = SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!
        repeat(3) {
            state = SubscriptionStateMachine.onClosed(state, structural = true, maxStructuralRefusals = 3)
            if (it < 2) {
                state = SubscriptionStateMachine.prepareRetry(state)
                state = SubscriptionStateMachine.reconcile(state, oldFilters, 1L).state!!
            }
        }

        assertEquals(RelaySubscriptionPhase.Suppressed, state.phase)
        assertNull(SubscriptionStateMachine.reconcile(state, oldFilters, 1L).command)
        assertIs<SubscriptionCommandDecision.Open>(
            SubscriptionStateMachine.reconcile(state, newFilters, 1L).command,
        )
    }

    @Test
    fun closedReasonsChooseSafeRetryPolicy() {
        assertEquals(
            RetryDisposition.RetryAfterAuth,
            classifyClosedReason("auth-required: challenge").disposition,
        )
        assertEquals(
            RetryDisposition.RetryWithBackoff,
            classifyClosedReason("rate-limited: slow down").disposition,
        )
        assertEquals(
            RetryDisposition.RetryOnFilterChange,
            classifyClosedReason("unsupported: filter").disposition,
        )
        assertEquals(
            RetryDisposition.DoNotRetry,
            classifyClosedReason("invalid: malformed").disposition,
        )
    }
}
