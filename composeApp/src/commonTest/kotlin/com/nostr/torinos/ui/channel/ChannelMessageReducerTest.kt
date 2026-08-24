package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ChannelMessageReducerTest {
    @Test
    fun messagesAreDeduplicatedAndSortedNewestFirst() {
        val old = event("old", 1)
        val latest = event("latest", 2)
        val merged = ChannelMessageReducer.received(listOf(old), latest)

        assertEquals(listOf("latest", "old"), merged.map { it.id })
        assertSame(merged, ChannelMessageReducer.received(merged, latest))
    }

    private fun event(id: String, createdAt: Long) =
        NostrEvent(id, "author", createdAt, 42, emptyList(), id, "sig")
}
