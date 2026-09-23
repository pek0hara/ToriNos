package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelayUrlNormalizationTest {
    @Test
    fun normalizesSchemeHostAndRootSlashButKeepsMeaningfulPath() {
        assertEquals("wss://relay.example", normalizeRelayUrl(" WSS://Relay.Example/ "))
        assertEquals("ws://relay.example:8080/path/", normalizeRelayUrl("WS://Relay.Example:8080/path/"))
        assertEquals("wss://[2001:db8::1]", normalizeRelayUrl("WSS://[2001:DB8::1]:443/"))
    }

    @Test
    fun omitsDefaultPortForSchemeButKeepsNonDefaultPort() {
        assertEquals("wss://relay.example", normalizeRelayUrl("wss://relay.example:443"))
        assertEquals("ws://relay.example", normalizeRelayUrl("ws://relay.example:80"))
        assertEquals("wss://relay.example:80", normalizeRelayUrl("wss://relay.example:80"))
        assertEquals("ws://relay.example:443", normalizeRelayUrl("ws://relay.example:443"))
        assertEquals(
            normalizeRelayUrl("wss://relay.example"),
            normalizeRelayUrl("wss://relay.example:443/"),
        )
    }

    @Test
    fun rejectsUnsupportedOrMalformedUrls() {
        assertNull(normalizeRelayUrl("https://relay.example"))
        assertNull(normalizeRelayUrl("wss://"))
        assertNull(normalizeRelayUrl("wss://user@relay.example"))
        assertNull(normalizeRelayUrl("wss://relay.example/#fragment"))
        assertNull(normalizeRelayUrl("wss://relay.example:not-a-port"))
    }

    @Test
    fun rejectsUrlsContainingQueryStrings() {
        assertNull(normalizeRelayUrl("wss://relay.example/path?foo=bar"))
        assertNull(normalizeRelayUrl("wss://relay.example?foo=bar"))
    }

    @Test
    fun listNormalizationPreservesOrderDeduplicatesAndLimits() {
        val input = buildList {
            add("WSS://A.EXAMPLE/")
            add("wss://a.example")
            add("https://invalid.example")
            repeat(12) { add("wss://relay-$it.example") }
        }

        assertEquals(
            listOf("wss://a.example") + List(9) { "wss://relay-$it.example" },
            normalizeRelayUrls(input),
        )
    }
}
