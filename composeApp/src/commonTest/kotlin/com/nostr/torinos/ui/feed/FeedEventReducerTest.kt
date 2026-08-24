package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FeedEventReducerTest {
    @Test
    fun historyAndLiveEventsMergeWithoutDuplicatesUsingTimelineTime() {
        val old = event("old", 10)
        val boosted = event("boosted", 5)
        val state = FeedEventReducer.insert(listOf(old), boosted, mapOf("boosted" to 20))

        assertEquals(listOf("boosted", "old"), state.map { it.id })
        assertSame(state, FeedEventReducer.insert(state, boosted, mapOf("boosted" to 20)))
    }

    private fun event(id: String, createdAt: Long) =
        NostrEvent(id, "author", createdAt, 1, emptyList(), id, "sig")
}
