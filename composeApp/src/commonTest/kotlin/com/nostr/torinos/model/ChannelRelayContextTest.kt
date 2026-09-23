package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelRelayContextTest {
    @Test
    fun recommendedRelaysLeadReadWriteAndPrimaryHint() {
        val context = ChannelRelayContextBuilder.build(
            recommendedRelays = listOf("WSS://A.EXAMPLE/", "wss://b.example"),
            navigationRelayHint = "wss://c.example",
            userReadRelays = listOf("wss://d.example"),
            userWriteRelays = listOf("wss://e.example"),
        )

        assertEquals(listOf("wss://a.example", "wss://b.example"), context.recommendedRelays)
        assertEquals(
            listOf("wss://a.example", "wss://b.example", "wss://c.example"),
            context.readRelays.toList(),
        )
        assertEquals(
            listOf("wss://a.example", "wss://b.example", "wss://e.example"),
            context.writeRelays.toList(),
        )
        assertEquals("wss://a.example", context.primaryHint)
    }

    @Test
    fun knownReadOnlyRecommendationIsHintButNotWriteTarget() {
        val context = ChannelRelayContextBuilder.build(
            recommendedRelays = listOf("wss://read-only.example"),
            navigationRelayHint = "wss://navigation.example",
            userReadRelays = emptyList(),
            userWriteRelays = listOf("wss://write.example"),
            nonWritableRelays = listOf("WSS://READ-ONLY.EXAMPLE/"),
        )

        assertEquals(setOf("wss://write.example"), context.writeRelays)
        assertEquals("wss://read-only.example", context.primaryHint)
    }

    @Test
    fun fallsBackFromNavigationToFirstUserWriteRelay() {
        assertEquals(
            "wss://navigation.example",
            ChannelRelayContextBuilder.build(
                emptyList(),
                "WSS://NAVIGATION.EXAMPLE/",
                emptyList(),
                listOf("wss://write.example"),
            ).primaryHint,
        )
        assertEquals(
            "wss://write.example",
            ChannelRelayContextBuilder.build(
                emptyList(),
                null,
                emptyList(),
                listOf("wss://write.example"),
            ).primaryHint,
        )
    }

    @Test
    fun blockedRecommendationsDoNotConsumeWriteLimit() {
        val blocked = List(10) { "wss://blocked-$it.example" }
        val context = ChannelRelayContextBuilder.build(
            recommendedRelays = blocked,
            navigationRelayHint = null,
            userReadRelays = emptyList(),
            userWriteRelays = listOf("wss://write.example"),
            nonWritableRelays = blocked,
        )

        assertEquals(setOf("wss://write.example"), context.writeRelays)
    }
}
