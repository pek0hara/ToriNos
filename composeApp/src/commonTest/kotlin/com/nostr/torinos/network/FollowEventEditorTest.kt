package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FollowEventEditorTest {
    @Test
    fun followAppendsPersonAndPreservesExistingTagsAndContent() {
        val original = event(
            tags = listOf(
                listOf("p", "alice", "wss://relay.example", "Alice"),
                listOf("t", "nostr"),
                listOf("client", "damus"),
            ),
            content = "{\"wss://relay.example\":{\"read\":true}}",
        )

        val edit = editFollowEvent(original, "bob", shouldFollow = true)!!

        assertEquals(original.content, edit.content)
        assertEquals(original.tags + listOf(listOf("p", "bob")), edit.tags)
    }

    @Test
    fun unfollowRemovesOnlyMatchingPersonTags() {
        val original = event(
            tags = listOf(
                listOf("p", "alice", "wss://relay.example", "Alice"),
                listOf("t", "bitcoin"),
                listOf("p", "bob"),
                listOf("p", "alice", "wss://backup.example"),
            ),
            content = "legacy relay content",
        )

        val edit = editFollowEvent(original, "alice", shouldFollow = false)!!

        assertEquals("legacy relay content", edit.content)
        assertEquals(listOf(listOf("t", "bitcoin"), listOf("p", "bob")), edit.tags)
    }

    @Test
    fun unchangedFollowOrUnfollowDoesNotCreateReplacement() {
        val original = event(tags = listOf(listOf("p", "alice")))

        assertNull(editFollowEvent(original, "alice", shouldFollow = true))
        assertNull(editFollowEvent(original, "bob", shouldFollow = false))
    }

    private fun event(
        tags: List<List<String>>,
        content: String = "",
    ) = NostrEvent(
        id = "event-id",
        pubkey = "pubkey",
        createdAt = 100L,
        kind = 3,
        tags = tags,
        content = content,
        sig = "signature",
    )
}
