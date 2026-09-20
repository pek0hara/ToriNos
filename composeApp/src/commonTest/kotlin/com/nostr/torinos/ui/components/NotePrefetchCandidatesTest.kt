package com.nostr.torinos.ui.components

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class NotePrefetchCandidatesTest {
    private fun event(id: String, tags: List<List<String>> = emptyList()) = NostrEvent(
        id = id,
        pubkey = "author",
        createdAt = 1L,
        kind = 1,
        tags = tags,
        content = id,
        sig = "sig",
    )

    @Test
    fun contentWarningPostsAreNotPrefetched() {
        val events = listOf(
            event("visible"),
            event("next-1"),
            event("warned", tags = listOf(listOf("content-warning", "nsfw"))),
            event("next-3"),
            event("beyond"),
        )

        val result = prefetchCandidateEvents(events, lastVisibleIndex = 0, aheadCount = 3)

        assertEquals(listOf("next-1", "next-3"), result.map { it.id })
    }

    @Test
    fun candidatesAreTheNextPostsAfterTheLastVisibleOne() {
        val events = (0 until 6).map { event("e$it") }

        val result = prefetchCandidateEvents(events, lastVisibleIndex = 1, aheadCount = 2)

        assertEquals(listOf("e2", "e3"), result.map { it.id })
    }

    @Test
    fun emptyWhenNothingFollowsTheLastVisiblePost() {
        val events = listOf(event("only"))

        assertEquals(emptyList(), prefetchCandidateEvents(events, lastVisibleIndex = 0, aheadCount = 3))
    }
}
