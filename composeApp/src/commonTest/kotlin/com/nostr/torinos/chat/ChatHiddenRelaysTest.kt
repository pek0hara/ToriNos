package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatHiddenRelaysTest {
    @Test
    fun encodeAndDecodeRoundTrip() {
        val hidden = setOf("wss://relay.damus.io", "wss://yabu.me")
        assertEquals(hidden, decodeHiddenRelays(encodeHiddenRelays(hidden)))
        assertNull(encodeHiddenRelays(emptySet()))
        assertEquals(emptySet(), decodeHiddenRelays(null))
    }

    @Test
    fun trailingSlashIsIgnored() {
        assertEquals(setOf("wss://yabu.me"), decodeHiddenRelays("wss://yabu.me/\n\n"))
        assertTrue(isHiddenRelay("wss://yabu.me/", setOf("wss://yabu.me")))
        assertFalse(isHiddenRelay("wss://relay.damus.io", setOf("wss://yabu.me")))
    }
}
