package com.nostr.torinos.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoundedLruCacheTest {
    @Test
    fun insertingPastLimitEvictsLeastRecentlyUsedEntry() {
        val cache = BoundedLruCache<String, Int>(maximumSize = 2)
        cache["old"] = 1
        cache["kept"] = 2

        assertEquals(1, cache["old"])
        cache["new"] = 3

        assertNull(cache["kept"])
        assertEquals(1, cache["old"])
        assertEquals(3, cache["new"])
        assertEquals(2, cache.size)
    }

    @Test
    fun replacingEntryDoesNotConsumeAnotherSlot() {
        val cache = BoundedLruCache<String, Int>(maximumSize = 1)

        cache["same"] = 1
        cache["same"] = 2

        assertEquals(2, cache["same"])
        assertEquals(1, cache.size)
    }
}
