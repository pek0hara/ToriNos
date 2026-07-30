package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomReactionTest {
    @Test
    fun parsesNip30CustomReaction() {
        val event = reactionEvent(
            content = ":torinos:",
            tags = listOf(
                listOf("e", "post-id"),
                listOf("emoji", "torinos", "https://example.com/torinos.webp"),
            ),
        )

        assertEquals(
            CustomReaction("torinos", "https://example.com/torinos.webp"),
            event.toCustomReaction(),
        )
    }

    @Test
    fun ignoresReactionWithoutMatchingEmojiTag() {
        val event = reactionEvent(
            content = ":torinos:",
            tags = listOf(listOf("emoji", "other", "https://example.com/other.webp")),
        )

        assertNull(event.toCustomReaction())
    }

    @Test
    fun aggregatesSameCustomReaction() {
        val reaction = CustomReaction("torinos", "https://example.com/torinos.webp")

        assertEquals(
            listOf(reaction.copy(count = 2)),
            listOf(reaction).incrementedWith(reaction),
        )
    }

    private fun reactionEvent(
        content: String,
        tags: List<List<String>>,
    ) = NostrEvent(
        id = "reaction-id",
        pubkey = "pubkey",
        createdAt = 1,
        kind = 7,
        tags = tags,
        content = content,
        sig = "signature",
    )
}
