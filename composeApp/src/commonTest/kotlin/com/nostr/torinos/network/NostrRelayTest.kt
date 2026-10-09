package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NostrRelayTest {
    @Test
    fun closeIsDequeuedBeforePendingRequestsAndReplacesSameSubscriptionRequest() {
        val queue = mutableListOf<PendingMessage>()
        queue.enqueue(buildPendingMessage("""["REQ","old",{}]"""))
        queue.enqueue(buildPendingMessage("""["REQ","new",{}]"""))

        queue.enqueue(buildPendingMessage("""["CLOSE","old"]"""))

        assertEquals(
            listOf("""["CLOSE","old"]""", """["REQ","new",{}]"""),
            queue.map { it.text },
        )
    }

    @Test
    fun requestDecidedBeforeConnectingIsNotQueued() {
        val outbox = RelayOutbox()

        // 接続前に決めた REQ は溜めない。接続後の再同期が1回だけ送る。
        assertFalse(outbox.offerControl(REQ_A, generation = 0))
        val session = outbox.openSession()
        assertTrue(outbox.offerControl(REQ_A, generation = session))

        assertEquals(listOf(REQ_A), outbox.drain())
    }

    @Test
    fun requestDecidedWithPreviousGenerationIsRejectedAfterConnecting() {
        val outbox = RelayOutbox()
        val session = outbox.openSession()

        // 接続後でも、再同期より前の世代で決めた REQ は再同期と重なるので送らない。
        assertFalse(outbox.offerControl(REQ_A, generation = session - 1))

        assertEquals(emptyList(), outbox.drain())
    }

    @Test
    fun pendingRequestsAreDiscardedOnDisconnectButEventsAreKept() {
        val outbox = RelayOutbox()
        val first = outbox.openSession()
        outbox.offer(buildPendingMessage(EVENT))
        assertTrue(outbox.offerControl(REQ_A, generation = first))

        outbox.closeSession()
        // 切断中は REQ を受け付けない。EVENT は次の接続で送る。
        assertFalse(outbox.offerControl(REQ_B, generation = first))
        val second = outbox.openSession()
        assertTrue(outbox.offerControl(REQ_B, generation = second))

        assertEquals(listOf(EVENT, REQ_B), outbox.drain())
    }

    @Test
    fun staleRequestLeftInQueueIsSkippedWhenDequeued() {
        val outbox = RelayOutbox()
        val first = outbox.openSession()
        assertTrue(outbox.offerControl(REQ_A, generation = first))

        // 切断の検知より先に次の接続が始まっても、前の接続の REQ は送らない。
        outbox.openSession()

        assertNull(outbox.next())
    }

    @Test
    fun failedSendIsRetriedOnlyWithinTheSameSession() {
        val outbox = RelayOutbox()
        val session = outbox.openSession()
        outbox.offer(buildPendingMessage(EVENT))
        assertTrue(outbox.offerControl(REQ_A, generation = session))

        val event = outbox.next()!!
        outbox.requeueFront(event)
        val request = outbox.drain()
        assertEquals(listOf(EVENT, REQ_A), request)

        assertTrue(outbox.offerControl(REQ_B, generation = session))
        val pending = outbox.next()!!
        outbox.requeueFront(pending)
        outbox.closeSession()
        outbox.openSession()

        assertEquals(emptyList(), outbox.drain())
    }

    private fun RelayOutbox.drain(): List<String> = generateSequence { next() }.map { it.text }.toList()

    private companion object {
        const val REQ_A = """["REQ","a",{}]"""
        const val REQ_B = """["REQ","b",{}]"""
        const val EVENT = """["EVENT",{"id":"x"}]"""
    }
}
