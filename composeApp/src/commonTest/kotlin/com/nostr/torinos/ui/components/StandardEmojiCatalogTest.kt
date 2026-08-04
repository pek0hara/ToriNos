package com.nostr.torinos.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandardEmojiCatalogTest {
    @Test
    fun containsUnicode17RgiEmojiSequencesWithoutSkinToneVariants() {
        val emojis = STANDARD_EMOJI_CATEGORIES.flatMap { it.emojis }

        assertEquals("17.0", STANDARD_EMOJI_VERSION)
        assertEquals(1_918, emojis.size)
        assertEquals(emojis.size, emojis.distinct().size)
    }

    @Test
    fun containsDefaultPeopleAndAllCategories() {
        val emojis = STANDARD_EMOJI_CATEGORIES.flatMap { it.emojis }.toSet()

        assertTrue("👍" in emojis)
        assertTrue("👩‍💻" in emojis)
        assertTrue("👨‍👩‍👧‍👦" in emojis)
        assertTrue("🇿🇼" in emojis)
        assertTrue("👍🏿" !in emojis)
        assertTrue("👩🏽‍💻" !in emojis)
    }
}
