package com.nostr.torinos.ui.timeline

import kotlin.test.Test
import kotlin.test.assertEquals

class StateStoreTest {
    @Test
    fun dispatchSeriallyReducesFromTheLatestState() {
        val store = StateStore(1)

        store.dispatch { it + 2 }
        store.dispatch { it * 3 }

        assertEquals(9, store.state.value)
    }
}
