package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemporaryRelaySubscriptionTest {
    @Test
    fun closingUnknownSubscriptionLeavesEveryTemporaryRelayUntouched() {
        val subscriptions = mutableMapOf(
            "relay-list" to ("filter-a" to "wss://relay-a.example"),
            "profile" to ("filter-b" to "wss://relay-b.example"),
        )
        val relays = mutableMapOf(
            "wss://relay-a.example" to "handle-a",
            "wss://relay-b.example" to "handle-b",
        )

        val result = removeTemporarySubscription(
            subscriptionId = "unknown",
            subscriptions = subscriptions,
            relays = relays,
        )

        assertNull(result)
        assertEquals(2, subscriptions.size)
        assertEquals(setOf("wss://relay-a.example", "wss://relay-b.example"), relays.keys)
    }

    @Test
    fun closingSharedSubscriptionKeepsRelayUntilLastSubscriptionCloses() {
        val subscriptions = mutableMapOf(
            "first" to ("filter-a" to "wss://relay.example"),
            "second" to ("filter-b" to "wss://relay.example"),
        )
        val relays = mutableMapOf("wss://relay.example" to "handle")

        val first = removeTemporarySubscription("first", subscriptions, relays)

        assertEquals("handle", first?.relayHandle)
        assertNull(first?.handleToClose)
        assertEquals(setOf("second"), subscriptions.keys)
        assertEquals(setOf("wss://relay.example"), relays.keys)

        val second = removeTemporarySubscription("second", subscriptions, relays)

        assertEquals("handle", second?.relayHandle)
        assertEquals("handle", second?.handleToClose)
        assertEquals(emptySet(), subscriptions.keys)
        assertEquals(emptySet(), relays.keys)
    }
}
