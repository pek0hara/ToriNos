package com.nostr.torinos.emoji

import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiShortcodeTest {
    @Test
    fun standaloneShortcodesSkipTimesAndWords() {
        val text = "12:30:45 に :party: と a:b: と (:ok:) こんにちは:wave:"

        assertEquals(listOf("party", "ok", "wave"), findStandaloneShortcodes(text).map { it.shortcode })
    }

    @Test
    fun standaloneShortcodeRangeCoversColons() {
        val match = findStandaloneShortcodes("x :cat:").single()

        assertEquals(":cat:", "x :cat:".substring(match.start, match.endExclusive))
    }
}
