package com.nostr.torinos.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Main.immediate のように同じスレッドでその場で再開する collector が、mailbox のロックを取り直しても止まらないこと。
 * iOS の NSLock は再入できないため、ロックを持ったまま再開させると同じスレッドで止まる。
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class SessionSignalMailboxReentryTest {
    @Test fun emitWakingSameThreadCollectorDoesNotDeadlock() = assertCompletesOnWorker {
        val mailbox = SessionSignalMailbox(false)
        mailbox.configure(setOf("a"), false)
        val received = CompletableDeferred<SubscriptionSignal>()
        val collector = launch(Dispatchers.Unconfined) { mailbox.signals.collect { received.complete(it) } }
        mailbox.emit(SubscriptionSignal.Eose("a"))
        assertIs<SubscriptionSignal.Eose>(received.await())
        mailbox.awaitApplied(mailbox.barrier())
        collector.cancel()
        mailbox.close()
    }

    @Test fun closeWakingSameThreadWaiterDoesNotDeadlock() = assertCompletesOnWorker {
        val mailbox = SessionSignalMailbox(false)
        mailbox.configure(setOf("a"), false)
        mailbox.emit(SubscriptionSignal.Eose("a"))
        val barrier = mailbox.barrier()
        val waiter = launch(Dispatchers.Unconfined) { mailbox.awaitApplied(barrier) }
        mailbox.close()
        waiter.join()
        assertEquals(SessionDeliveryState.Closed, mailbox.deliveryState.value)
    }

    @Test fun gapWakingSameThreadObserverDoesNotDeadlock() = assertCompletesOnWorker {
        val mailbox = SessionSignalMailbox(false, capacity = 1)
        mailbox.configure(setOf("a"), false)
        val seen = CompletableDeferred<RecoveryBarrier>()
        val observer = launch(Dispatchers.Unconfined) {
            mailbox.deliveryState.first { it is SessionDeliveryState.NeedsResync }
            seen.complete(mailbox.barrier())
        }
        repeat(2) { mailbox.emit(SubscriptionSignal.Eose("a")) }
        assertEquals(1L, seen.await().revision)
        observer.join()
        mailbox.close()
    }

    @Test fun signalEmittedFromSameThreadHandlerKeepsOrder() = assertCompletesOnWorker {
        val mailbox = SessionSignalMailbox(false)
        mailbox.configure(setOf("a", "b"), false)
        val received = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.Unconfined) {
            mailbox.signals.collect { signal ->
                val relay = (signal as SubscriptionSignal.Eose).relayUrl
                received += relay
                if (relay == "a") mailbox.emit(SubscriptionSignal.Eose("b"))
                if (relay == "b") done.complete(Unit)
            }
        }
        mailbox.emit(SubscriptionSignal.Eose("a"))
        done.await()
        assertEquals(listOf("a", "b"), received)
        assertEquals(SessionDeliveryState.Healthy, mailbox.deliveryState.value)
        collector.cancel()
        mailbox.close()
    }

    /** 止まった場合にテスト全体が止まらないよう、別スレッドで動かして時間切れを失敗にする。 */
    private fun assertCompletesOnWorker(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val worker = newSingleThreadContext("mailbox-reentry")
        val result = CoroutineScope(worker).async { block() }
        val finished = withTimeoutOrNull(5_000) { result.await(); true } ?: false
        // 止まったスレッドの終了待ちで本体まで止まるため、完了したときだけ閉じる。
        if (finished) worker.close()
        assertTrue(finished, "同じスレッドで再開した処理が mailbox のロックを取り直して止まった")
    }
}
