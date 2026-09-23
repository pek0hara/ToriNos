package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelayInformationRepositoryTest {
    @Test
    fun toRelayInformationUrl_convertsWssToHttps() {
        assertEquals(
            "https://relay.example.com/path",
            "wss://relay.example.com/path".toRelayInformationUrl(),
        )
    }

    @Test
    fun toRelayInformationUrl_convertsWsToHttp() {
        assertEquals(
            "http://relay.example.com/path",
            "ws://relay.example.com/path".toRelayInformationUrl(),
        )
    }

    @Test
    fun toRelayInformationUrl_keepsHttpsAndHttpAsIs() {
        assertEquals("https://relay.example.com", "https://relay.example.com".toRelayInformationUrl())
        assertEquals("http://relay.example.com", "http://relay.example.com".toRelayInformationUrl())
    }

    @Test
    fun toRelayInformationUrl_rejectsUnsupportedScheme() {
        assertFailsWith<IllegalStateException> { "ftp://relay.example.com".toRelayInformationUrl() }
    }
}
