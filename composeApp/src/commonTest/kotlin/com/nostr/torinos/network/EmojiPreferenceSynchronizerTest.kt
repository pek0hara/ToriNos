package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiPreferenceSynchronizerTest {
    @Test
    fun parsesPreferredEmojisAndEmojiSetPointers() {
        val result = parseEmojiPreferenceTags(
            listOf(
                listOf("emoji", "blobcat", "https://example.com/blobcat.png"),
                listOf("a", "30030:${"a".repeat(64)}:cats"),
                listOf("a", "30023:${"b".repeat(64)}:article"),
                listOf("emoji", "", "https://example.com/invalid.png"),
            ),
        )

        assertEquals(
            listOf(CustomEmoji("blobcat", "https://example.com/blobcat.png")),
            result.emojis,
        )
        assertEquals(listOf("30030:${"a".repeat(64)}:cats"), result.setReferences)
    }

    @Test
    fun replacesEmojiPreferencesWhilePreservingUnknownTags() {
        val reference = "30030:${"c".repeat(64)}:animals"
        val tags = buildEmojiPreferenceTags(
            previousTags = listOf(
                listOf("client", "another-client"),
                listOf("emoji", "old", "https://example.com/old.png"),
                listOf("a", "30030:${"d".repeat(64)}:old"),
            ),
            emojis = listOf(CustomEmoji(":new:", "https://example.com/new.png")),
            setReferences = listOf(reference),
        )

        assertEquals(
            listOf(
                listOf("client", "another-client"),
                listOf("emoji", "new", "https://example.com/new.png"),
                listOf("a", reference),
            ),
            tags,
        )
    }

    @Test
    fun convertsStoredPublishedSetIdToNip51Address() {
        val author = "e".repeat(64)
        val list = CustomEmojiList(
            id = "$author:cats",
            name = "Cats",
            emojis = listOf(CustomEmoji("cat", "https://example.com/cat.png")),
            authorPubkey = author,
        )

        assertEquals("30030:$author:cats", list.toEmojiSetReference())
    }
}
