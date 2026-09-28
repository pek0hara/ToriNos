package com.nostr.torinos.emoji

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmojiSetDiscoveryTest {
    private val authorA = "a".repeat(64)
    private val authorB = "b".repeat(64)

    @Test
    fun deduplicatePublishedEmojiSets_keepsNewestEventForSameAddress() {
        val old = emojiSet(identifier = "list", eventId = "old", createdAt = 10)
        val newest = emojiSet(identifier = "list", eventId = "new", createdAt = 20)

        assertEquals(listOf(newest), deduplicatePublishedEmojiSets(listOf(old, newest)))
    }

    @Test
    fun deduplicatePublishedEmojiSets_mergesRecreatedSetWithSameAuthorAndName() {
        val old = emojiSet(identifier = "old-list", eventId = "old", createdAt = 10)
        val recreated = emojiSet(identifier = "new-list", eventId = "new", createdAt = 20)

        assertEquals(listOf(recreated), deduplicatePublishedEmojiSets(listOf(old, recreated)))
    }

    @Test
    fun deduplicatePublishedEmojiSets_usesLowestEventIdWhenTimestampsMatch() {
        val higherId = emojiSet(identifier = "list", eventId = "bbbb", createdAt = 20)
        val lowerId = emojiSet(identifier = "list", eventId = "aaaa", createdAt = 20)

        assertEquals(listOf(lowerId), deduplicatePublishedEmojiSets(listOf(higherId, lowerId)))
        assertEquals(true, lowerId.isPreferredTo(higherId))
        assertEquals(false, higherId.isPreferredTo(lowerId))
    }

    @Test
    fun deduplicatePublishedEmojiSets_keepsSameNameFromDifferentAuthors() {
        val first = emojiSet(identifier = "list", eventId = "a", author = authorA)
        val second = emojiSet(identifier = "list", eventId = "b", author = authorB)

        assertEquals(2, deduplicatePublishedEmojiSets(listOf(first, second)).size)
    }

    @Test
    fun parsesKind30030EventWithTitleFallbackToIdentifier() {
        val event = NostrEvent(
            id = "id",
            pubkey = authorA,
            createdAt = 5,
            kind = 30030,
            tags = listOf(
                listOf("d", "cats"),
                listOf("emoji", "cat", "https://example.com/cat.png"),
                listOf("emoji", "cat", "https://example.com/cat.png"),
            ),
            content = "",
            sig = "sig",
        )

        val set = event.toPublishedEmojiSet()

        assertEquals(EmojiSetAddress(authorA, "cats"), set?.address)
        assertEquals("cats", set?.name)
        assertEquals(listOf(CustomEmoji("cat", "https://example.com/cat.png")), set?.emojis)
        assertNull(event.copy(tags = listOf(listOf("d", "cats"))).toPublishedEmojiSet())
    }

    private fun emojiSet(
        identifier: String,
        eventId: String,
        createdAt: Long = 10,
        author: String = authorA,
    ) = PublishedEmojiSet(
        address = EmojiSetAddress(author, identifier),
        sourceEventId = eventId,
        name = "Blob Cats Emojis - Animations",
        createdAt = createdAt,
        emojis = listOf(CustomEmoji("blobcat", "https://example.com/blobcat.png")),
    )
}
