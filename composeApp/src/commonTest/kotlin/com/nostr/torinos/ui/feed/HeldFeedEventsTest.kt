package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeldFeedEventsTest {
    @Test
    fun evictsOldestInsertedBeyondTheLimit() {
        val held = HeldFeedEvents(maxSize = 2) { it.createdAt }
        held.add(event("a", 10), emptyList())
        held.add(event("b", 20), emptyList())

        val evicted = held.add(event("c", 30), emptyList())

        assertEquals(listOf("a"), evicted.map { it.id })
        assertNull(held["a"])
        assertEquals(setOf("b", "c"), held.snapshot().resolve())
    }

    @Test
    fun newestRevealableIsRecomputedOnlyWhenTheNewestLeaves() {
        val held = HeldFeedEvents(maxSize = 10) { it.createdAt }
        val filtered = mutableSetOf<String>()
        listOf(event("a", 10), event("b", 30), event("c", 20)).forEach {
            held.add(it, emptyList())
            held.noteRevealable(it.createdAt)
        }
        var scans = 0
        val isFiltered: (NostrEvent) -> Boolean = { scans++; it.id in filtered }

        held.remove("a")
        assertEquals(30, held.newestRevealable(isFiltered))
        assertEquals(0, scans, "最大値以外が抜けても全件は見直さない")

        held.remove("b")
        assertEquals(20, held.newestRevealable(isFiltered))

        filtered += "c"
        held.invalidateRevealable()
        assertNull(held.newestRevealable(isFiltered))
    }

    private fun event(id: String, createdAt: Long) =
        NostrEvent(id = id, pubkey = "p", createdAt = createdAt, kind = 1, tags = emptyList(), content = "", sig = "")
}
