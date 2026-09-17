package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
