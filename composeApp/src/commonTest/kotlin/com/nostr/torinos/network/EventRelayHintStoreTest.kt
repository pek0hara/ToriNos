package com.nostr.torinos.network

import kotlin.test.*

class EventRelayHintStoreTest {
    @Test fun boundedStoreUsesOnlyObservedWebsocketUrls() {
        val store = EventRelayHintStore(2)
        store.observe("a", "wss://one")
        store.observe("b", "wss://two")
        store.observe("a", "wss://three")
        store.observe("c", "wss://four")
        assertNull(store.get("b"))
        assertEquals("wss://three", store.get("a"))
        assertEquals(setOf("wss://one", "wss://three"), store.getAll("a"))
        store.observe("a", "https://invalid")
        assertEquals("wss://three", store.get("a"))
        assertNull(store.get("unknown"))
        assertTrue(store.getAll("unknown").isEmpty())
    }

    @Test fun boundsRelayCountWithoutChangingLatestHint() {
        val store = EventRelayHintStore()
        repeat(40) { store.observe("post", "wss://relay$it") }
        assertEquals(32, store.getAll("post").size)
        assertEquals("wss://relay39", store.get("post"))
        assertFalse("wss://relay0" in store.getAll("post"))
    }
}
