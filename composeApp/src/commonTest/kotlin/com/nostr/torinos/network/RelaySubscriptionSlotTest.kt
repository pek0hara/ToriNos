package com.nostr.torinos.network

import com.nostr.torinos.model.NostrFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelaySubscriptionSlotTest {
    private val filters = listOf(NostrFilter(kinds = listOf(1)))
    private val updated = listOf(NostrFilter(kinds = listOf(1), since = 10L))

    @Test
    fun newSubscriptionWaitsWhileTheRelayHasNoFreeSlot() {
        val waiting = SubscriptionStateMachine.reconcile(null, filters, 1L, hasFreeSlot = false)

        assertNull(waiting.command)
        assertEquals(RelaySubscriptionPhase.Idle, waiting.state?.phase)
        assertNull(waiting.state?.sentFilters)
        assertFalse(RelaySubscriptionSlots.occupies(waiting.state))
    }

    @Test
    fun waitingSubscriptionOpensOnceASlotFrees() {
        val waiting = SubscriptionStateMachine.reconcile(null, filters, 1L, hasFreeSlot = false).state

        val opened = SubscriptionStateMachine.reconcile(waiting, filters, 1L, hasFreeSlot = true)

        assertEquals(filters, assertIs<SubscriptionCommandDecision.Open>(opened.command).filters)
        assertTrue(RelaySubscriptionSlots.occupies(opened.state))
    }

    @Test
    fun updatingAnOpenSubscriptionReusesItsSlot() {
        // 同じ購読IDの REQ はリレー上で置き換わるので、空きがなくても送ってよい。
        val live = SubscriptionStateMachine.onEose(SubscriptionStateMachine.reconcile(null, filters, 1L).state!!)

        val result = SubscriptionStateMachine.reconcile(live, updated, 1L, hasFreeSlot = false)

        assertEquals(updated, assertIs<SubscriptionCommandDecision.Open>(result.command).filters)
    }

    @Test
    fun closedSubscriptionNoLongerOccupiesASlot() {
        val sent = SubscriptionStateMachine.reconcile(null, filters, 1L).state!!
        val closed = SubscriptionStateMachine.onClosed(sent, structural = false, maxStructuralRefusals = 3)

        assertTrue(RelaySubscriptionSlots.occupies(sent))
        assertFalse(RelaySubscriptionSlots.occupies(closed))
        // 拒否された購読を条件を変えて開き直すには、新しく空きが要る。
        assertNull(SubscriptionStateMachine.reconcile(closed, updated, 1L, hasFreeSlot = false).command)
    }

    @Test
    fun limitComesFromRelayInformationAndFallsBackToConservativeDefault() {
        assertEquals(50, RelaySubscriptionSlots.limit(advertised = 50, lowered = null))
        assertEquals(RelaySubscriptionSlots.DEFAULT_LIMIT, RelaySubscriptionSlots.limit(advertised = null, lowered = null))
        assertEquals(6, RelaySubscriptionSlots.limit(advertised = 50, lowered = 6))
        assertEquals(1, RelaySubscriptionSlots.limit(advertised = 0, lowered = null))
    }

    @Test
    fun recognizesTooManySubscriptionNotices() {
        assertTrue(RelaySubscriptionSlots.isTooManySubscriptionsNotice("ERROR: too many concurrent REQs"))
        assertTrue(RelaySubscriptionSlots.isTooManySubscriptionsNotice("Maximum concurrent subscription count reached"))
        assertTrue(RelaySubscriptionSlots.isTooManySubscriptionsNotice("too many subscriptions"))
        assertFalse(RelaySubscriptionSlots.isTooManySubscriptionsNotice("Subscription rejected: Duplicate subscription x: Ignoring"))
        assertFalse(RelaySubscriptionSlots.isTooManySubscriptionsNotice("ERROR: bad req: arr too big"))
    }
}
