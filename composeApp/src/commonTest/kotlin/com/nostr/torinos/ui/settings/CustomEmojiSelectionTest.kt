package com.nostr.torinos.ui.settings

import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.emoji.PublishedEmojiSet
import com.nostr.torinos.emoji.RegisteredEmojiSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomEmojiSelectionTest {
    private val first = CustomEmoji("first", "https://example.com/first.webp")
    private val requested = CustomEmoji("take", "https://example.com/take.webp")
    private val emojis = listOf(first, requested)

    @Test
    fun selectsExactShortcodeAndImageUrlMatch() {
        assertEquals(
            requested,
            selectInitialEmoji(emojis, ":take:", requested.imageUrl),
        )
    }

    @Test
    fun fallsBackToShortcodeWhenImageUrlDiffers() {
        assertEquals(
            requested,
            selectInitialEmoji(emojis, "take", "https://proxy.example.com/take.webp"),
        )
    }

    @Test
    fun fallsBackToFirstEmojiWhenShortcodeIsMissing() {
        assertEquals(first, selectInitialEmoji(emojis, "missing", ""))
    }

    private val author = "a".repeat(64)
    private val registeredSet = RegisteredEmojiSet(EmojiSetAddress(author, "mine"), "Mine", listOf(first))
    private val publishedSet = PublishedEmojiSet(EmojiSetAddress(author, "pub"), "e", "Pub", 1, listOf(requested))
    private val otherPublished = PublishedEmojiSet(
        EmojiSetAddress(author, "other"),
        "f",
        "Other",
        1,
        listOf(CustomEmoji("take", "https://example.com/other.webp")),
    )

    @Test
    fun requestedSetPrefersAddress() {
        val found = findRequestedEmojiSet(
            shortcode = "take",
            imageUrl = requested.imageUrl,
            address = registeredSet.address,
            registered = listOf(registeredSet),
            published = listOf(publishedSet),
            isPublishedLoading = true,
        )

        assertEquals(registeredSet.address, found?.address)
    }

    @Test
    fun requestedSetMatchesShortcodeAndUrl() {
        val found = findRequestedEmojiSet(":take:", requested.imageUrl, null, listOf(registeredSet), listOf(otherPublished, publishedSet), true)

        assertEquals(publishedSet.address, found?.address)
    }

    @Test
    fun requestedSetFallsBackToUniqueShortcodeOnlyAfterLoading() {
        val url = "https://example.com/unknown.webp"

        assertNull(findRequestedEmojiSet("take", url, null, emptyList(), listOf(publishedSet), isPublishedLoading = true))
        assertEquals(
            publishedSet.address,
            findRequestedEmojiSet("take", url, null, emptyList(), listOf(publishedSet), isPublishedLoading = false)?.address,
        )
        assertNull(findRequestedEmojiSet("take", url, null, emptyList(), listOf(publishedSet, otherPublished), false))
    }

    @Test
    fun unknownImageOnlyFillsTheSearchQuery() {
        assertNull(findRequestedEmojiSet("take", "", null, emptyList(), listOf(publishedSet), false))
    }
}
