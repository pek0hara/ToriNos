package com.nostr.torinos.ui.settings

import com.nostr.torinos.network.CustomEmoji
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomEmojiSettingsViewModelTest {
    @Test
    fun deduplicatePublishedEmojiSets_keepsNewestEventForSameAddress() {
        val old = emojiSet(id = "author:list", eventId = "old", createdAt = 10)
        val newest = emojiSet(id = "author:list", eventId = "new", createdAt = 20)

        assertEquals(listOf(newest), deduplicatePublishedEmojiSets(listOf(old, newest)))
    }

    @Test
    fun deduplicatePublishedEmojiSets_mergesRecreatedSetWithSameAuthorAndName() {
        val old = emojiSet(id = "author:old-list", eventId = "old", createdAt = 10)
        val recreated = emojiSet(id = "author:new-list", eventId = "new", createdAt = 20)

        assertEquals(listOf(recreated), deduplicatePublishedEmojiSets(listOf(old, recreated)))
    }

    @Test
    fun deduplicatePublishedEmojiSets_usesLowestEventIdWhenTimestampsMatch() {
        val higherId = emojiSet(id = "author:list", eventId = "bbbb", createdAt = 20)
        val lowerId = emojiSet(id = "author:list", eventId = "aaaa", createdAt = 20)

        assertEquals(listOf(lowerId), deduplicatePublishedEmojiSets(listOf(higherId, lowerId)))
        assertEquals(true, lowerId.isPreferredTo(higherId))
        assertEquals(false, higherId.isPreferredTo(lowerId))
    }

    @Test
    fun deduplicatePublishedEmojiSets_keepsSameNameFromDifferentAuthors() {
        val first = emojiSet(id = "author-a:list", eventId = "a", author = "author-a")
        val second = emojiSet(id = "author-b:list", eventId = "b", author = "author-b")

        assertEquals(2, deduplicatePublishedEmojiSets(listOf(first, second)).size)
    }

    private fun emojiSet(
        id: String,
        eventId: String,
        createdAt: Long = 10,
        author: String = "author",
    ) = PublishedEmojiSet(
        id = id,
        sourceEventId = eventId,
        name = "Blob Cats Emojis - Animations",
        authorPubkey = author,
        createdAt = createdAt,
        emojis = listOf(CustomEmoji("blobcat", "https://example.com/blobcat.png")),
    )
}
