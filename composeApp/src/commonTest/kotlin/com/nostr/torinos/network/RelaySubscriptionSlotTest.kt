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
    fun finishedFetchClosesOnThatRelayAndFreesItsSlot() {
        // 有限取得は EOSE を返したリレーから順に閉じる。他のリレーの応答を待つ間も枠をふさがない。
        val live = SubscriptionStateMachine.onEose(SubscriptionStateMachine.reconcile(null, filters, 1L).state!!)

        val finished = SubscriptionStateMachine.finishFetch(live)

        assertIs<SubscriptionCommandDecision.Close>(finished.command)
        assertEquals(RelaySubscriptionPhase.Finished, finished.state?.phase)
        assertNull(finished.state?.sentFilters)
        assertFalse(RelaySubscriptionSlots.occupies(finished.state))
    }

    @Test
    fun finishedFetchIsNotOpenedAgain() {
        val live = SubscriptionStateMachine.onEose(SubscriptionStateMachine.reconcile(null, filters, 1L).state!!)
        val finished = SubscriptionStateMachine.finishFetch(live).state!!

        assertNull(SubscriptionStateMachine.reconcile(finished, filters, 1L).command)
        // 再接続しても、取得し終えたリレーへ同じ REQ を送り直さない。
        val reconnected = SubscriptionStateMachine.onDisconnected(finished, nextConnectionGeneration = 2L)
        assertEquals(RelaySubscriptionPhase.Finished, reconnected.phase)
        assertNull(SubscriptionStateMachine.reconcile(reconnected, filters, 2L).command)
    }

    @Test
    fun fetchClosedByTheRelayFinishesWithoutSendingClose() {
        val sent = SubscriptionStateMachine.reconcile(null, filters, 1L).state!!
        val closed = SubscriptionStateMachine.onClosed(sent, structural = false, maxStructuralRefusals = 3)

        val finished = SubscriptionStateMachine.finishFetch(closed)

        assertNull(finished.command)
        assertEquals(RelaySubscriptionPhase.Finished, finished.state?.phase)
        assertNull(SubscriptionStateMachine.reconcile(finished.state, filters, 1L).command)
    }

    @Test
    fun eachTooManyNoticeRequeuesTheLatestUnansweredSubscription() {
        // 上限を超えた REQ は送った順の後ろから拒否され、拒否1件ごとに通知が1つ届く。
        val order = RelayOpenOrder()
        listOf("a", "b", "c", "d").forEach { order.opened("wss://r", it) }
        val unanswered = mutableSetOf("b", "c", "d")

        assertEquals("d", order.rejectedByTooManyNotice("wss://r") { it in unanswered })
        assertEquals("c", order.rejectedByTooManyNotice("wss://r") { it in unanswered })
        // 応答を返し始めた購読は拒否されていないので、待ちに戻さない。
        unanswered -= "b"
        assertNull(order.rejectedByTooManyNotice("wss://r") { it in unanswered })
    }

    @Test
    fun reopenedSubscriptionMovesToTheEndOfTheOpenOrder() {
        val order = RelayOpenOrder()
        listOf("a", "b").forEach { order.opened("wss://r", it) }
        order.opened("wss://r", "a")
        order.opened("wss://other", "z")

        assertEquals("a", order.rejectedByTooManyNotice("wss://r") { true })
        order.forget("b")
        assertNull(order.rejectedByTooManyNotice("wss://r") { true })
        assertEquals("z", order.rejectedByTooManyNotice("wss://other") { true })
    }

    @Test
    fun loweredLimitFollowsTheSubscriptionsStillOpen() {
        assertEquals(7, RelaySubscriptionSlots.lowered(previous = null, stillOpen = 7))
        assertEquals(5, RelaySubscriptionSlots.lowered(previous = 6, stillOpen = 5))
        // 一度下げた上限は、後の通知で上げ直さない。
        assertEquals(5, RelaySubscriptionSlots.lowered(previous = 5, stillOpen = 6))
        assertEquals(1, RelaySubscriptionSlots.lowered(previous = null, stillOpen = 0))
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
