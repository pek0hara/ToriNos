package com.nostr.torinos.ui.settings

import com.nostr.torinos.network.CustomEmoji
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
