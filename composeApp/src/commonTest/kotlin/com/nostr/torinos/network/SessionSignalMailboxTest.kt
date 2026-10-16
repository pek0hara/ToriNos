package com.nostr.torinos.network

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionSignalMailboxTest {
    @Test fun overflowIsStickyUntilEarlierSignalsAreApplied() = runTest {
        val mailbox = SessionSignalMailbox(false, capacity = 2)
        mailbox.configure(setOf("relay"), false)
        repeat(3) { mailbox.emit(SubscriptionSignal.Eose("relay")) }
        assertIs<SessionDeliveryState.NeedsResync>(mailbox.deliveryState.value)
        val barrier = mailbox.barrier()
        assertFalse(mailbox.acknowledge(barrier))
        var applied = 0
        val collector = backgroundScope.launch { mailbox.signals.collect { applied++ } }
        runCurrent()
        mailbox.awaitApplied(barrier)
        assertEquals(2, applied)
        assertTrue(mailbox.acknowledge(barrier))
        assertEquals(SessionDeliveryState.Healthy, mailbox.deliveryState.value)
        collector.cancel()
        mailbox.close()
    }

    @Test fun oldRecoveryCannotClearNewGapOrTargetGeneration() = runTest {
        val mailbox = SessionSignalMailbox(false, capacity = 1)
        mailbox.configure(setOf("a", "b"), false)
        repeat(2) { mailbox.emit(SubscriptionSignal.Eose("a")) }
        val first = mailbox.barrier()
        mailbox.emit(SubscriptionSignal.Eose("b"))
        val second = mailbox.barrier()
        backgroundScope.launch { mailbox.signals.collect {} }
        runCurrent()
        assertFalse(mailbox.acknowledge(first))
        mailbox.configure(setOf("b"), true)
        assertFalse(mailbox.acknowledge(second))
        assertTrue(mailbox.acknowledge(mailbox.barrier()))
        mailbox.close()
    }

    @Test fun handlerFailureRequiresRecoveryAndClosedMailboxDoesNotReopen() = runTest {
        val mailbox = SessionSignalMailbox(false)
        mailbox.configure(setOf("a"), false)
        mailbox.emit(SubscriptionSignal.Eose("a"))
        assertFailsWith<IllegalStateException> { mailbox.signals.collect { error("handler failed") } }
        assertIs<SessionDeliveryState.NeedsResync>(mailbox.deliveryState.value)
        val barrier = mailbox.barrier()
        mailbox.awaitApplied(barrier)
        assertTrue(mailbox.acknowledge(barrier))
        mailbox.close()
        mailbox.emit(SubscriptionSignal.Eose("a"))
        assertEquals(SessionDeliveryState.Closed, mailbox.deliveryState.value)
    }

    @Test fun connectionPoolDoesNotRecreateRetainedHandles() {
        var created = 0
        val pool = RelayConnectionPool { _: String -> ++created }
        assertEquals(emptyList(), pool.reconcile(setOf("a")).first)
        val old = pool.active.getValue("a")
        val (removed, added) = pool.reconcile(setOf("a", "b"))
        assertTrue(removed.isEmpty())
        assertEquals(1, added.size)
        assertEquals(old, pool.active["a"])
        assertEquals(listOf(old), pool.reconcile(setOf("b")).first)
    }
}
