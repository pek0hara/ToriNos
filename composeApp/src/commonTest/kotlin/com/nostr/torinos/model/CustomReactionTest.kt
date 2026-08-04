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

    @Test
    fun parsesUnicodeReaction() {
        assertEquals(
            UnicodeReaction("🎉"),
            reactionEvent(content = "🎉", tags = emptyList()).toUnicodeReaction(),
        )
    }

    @Test
    fun customReactionIsNotDuplicatedAsUnicodeReaction() {
        val event = reactionEvent(
            content = ":torinos:",
            tags = listOf(listOf("emoji", "torinos", "https://example.com/torinos.webp")),
        )

        assertNull(event.toUnicodeReaction())
    }

    @Test
    fun plainTextReactionIsNotDisplayedAsUnicodeEmoji() {
        assertNull(reactionEvent(content = "hello", tags = emptyList()).toUnicodeReaction())
    }

    @Test
    fun aggregatesSameUnicodeReaction() {
        val reaction = UnicodeReaction("🎉")

        assertEquals(
            listOf(reaction.copy(count = 2)),
            listOf(reaction).incrementedWithUnicodeReaction(reaction),
        )
    }

    @Test
    fun customReactionOptionBuildsNip30Event() {
        val option = ReactionOption.Custom(
            shortcode = "torinos",
            imageUrl = "https://example.com/torinos.webp",
        )

        assertEquals(":torinos:", option.eventContent)
        assertEquals(
            listOf(
                listOf("e", "post-id"),
                listOf("p", "author-pubkey"),
                listOf("emoji", "torinos", "https://example.com/torinos.webp"),
            ),
            option.eventTags("post-id", "author-pubkey"),
        )
    }

    @Test
    fun decrementsAndRemovesEmojiReactionAtZero() {
        val unicodeOption = ReactionOption.Unicode("🎉")
        val customOption = ReactionOption.Custom(
            shortcode = "torinos",
            imageUrl = "https://example.com/torinos.webp",
        )

        assertEquals(
            emptyList(),
            listOf(UnicodeReaction("🎉")).decrementedWithUnicodeReaction(unicodeOption),
        )
        assertEquals(
            emptyList(),
            listOf(CustomReaction("torinos", customOption.imageUrl)).decrementedWith(customOption),
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
