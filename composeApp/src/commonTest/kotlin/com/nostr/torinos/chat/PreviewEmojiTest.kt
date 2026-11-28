package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewEmojiTest {
    private fun event(vararg tags: List<String>) =
        NostrEvent(id = "e", pubkey = "p", createdAt = 1, kind = 9, tags = tags.toList(), content = "", sig = "")

    @Test
    fun mapsEmojiTagsByLowercaseShortcode() {
        val map = previewEmojiMap(event(listOf("emoji", "Gloopies-2", "https://x/g.png"), listOf("p", "abc")))
        assertEquals(mapOf("gloopies-2" to "https://x/g.png"), map)
    }

    @Test
    fun findsOnlyShortcodesThatHaveImages() {
        val text = "あ :gloopies-2: と :unknown: と :GLOOPIES-2:"
        val found = previewEmojiMatches(text, setOf("gloopies-2"))
        assertEquals(listOf(2..13 to "gloopies-2", 29..40 to "gloopies-2"), found)
    }

    @Test
    fun noImagesMeansNoMatches() {
        assertEquals(emptyList(), previewEmojiMatches(":a:", emptySet()))
    }
}
