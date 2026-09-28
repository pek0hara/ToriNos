package com.nostr.torinos.emoji

import com.nostr.torinos.network.CustomEmoji
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomEmojiTagsTest {
    @Test
    fun customEmojiMapReadsValidTagsAndTrimsColons() {
        val tags = listOf(
            listOf("emoji", ":blob-cat:", " https://example.com/blob.png "),
            listOf("emoji", "", "https://example.com/blank.png"),
            listOf("emoji", "nourl"),
            listOf("p", "abc"),
        )

        assertEquals(mapOf("blob-cat" to "https://example.com/blob.png"), tags.customEmojiMap())
    }

    @Test
    fun customEmojiMapIgnoresSetAddressInFourthElement() {
        val tags = listOf(listOf("emoji", "cat", "https://example.com/cat.png", "30030:${"a".repeat(64)}:cats"))

        assertEquals(mapOf("cat" to "https://example.com/cat.png"), tags.customEmojiMap())
    }

    @Test
    fun contentTagsIncludeOnlyUsedKnownCodesOnce() {
        val emojis = listOf(
            CustomEmoji("cat", "https://example.com/cat.png"),
            CustomEmoji("dog_2", "https://example.com/dog.png"),
            CustomEmoji("unused", "https://example.com/unused.png"),
        )

        assertEquals(
            listOf(
                listOf("emoji", "cat", "https://example.com/cat.png"),
                listOf("emoji", "dog_2", "https://example.com/dog.png"),
            ),
            customEmojiTagsForContent(":cat: :dog_2: :cat: :unknown:", emojis),
        )
    }

    @Test
    fun contentTagsUseFirstEmojiWhenShortcodesCollide() {
        val emojis = listOf(
            CustomEmoji("cat", "https://example.com/first.png"),
            CustomEmoji("cat", "https://example.com/second.png"),
        )

        // associateBy は後勝ち。S1 以降で既定解決を明示するまで現行挙動として固定する。
        assertEquals(
            listOf(listOf("emoji", "cat", "https://example.com/second.png")),
            customEmojiTagsForContent(":cat:", emojis),
        )
    }
}
