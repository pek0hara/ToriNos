package com.nostr.torinos.network

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionSessionDeliveryTest {
    @Test
    fun finiteSessionRetainsSignalsBeyondLegacyBufferSize() = runTest {
        val session = SubscriptionSessionImpl(id = "finite", lossless = true)
        repeat(600) { index ->
            session.emit(SubscriptionSignal.Eose("relay-$index"))
        }

        val received = session.signals.take(600).toList()

        assertEquals(600, received.size)
    }
}
