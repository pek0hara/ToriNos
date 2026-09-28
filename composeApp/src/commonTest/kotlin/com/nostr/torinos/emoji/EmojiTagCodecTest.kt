package com.nostr.torinos.emoji

import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiTagCodecTest {
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

        // 呼び出し側が優先順に渡すので、先に並ぶものを使う。
        assertEquals(
            listOf(listOf("emoji", "cat", "https://example.com/first.png")),
            customEmojiTagsForContent(":cat:", emojis),
        )
    }

    @Test
    fun emojiTagsKeepTagOrderAndDuplicates() {
        val tags = listOf(
            listOf("emoji", "b", "https://example.com/b.png"),
            listOf("emoji", "a", "https://example.com/a.png"),
            listOf("emoji", "b", "https://example.com/b.png"),
        )

        assertEquals(listOf("b", "a", "b"), tags.emojiTags().map { it.shortcode })
    }

    @Test
    fun toEmojiTagNormalizesShortcodeAndUrl() {
        assertEquals(
            listOf("emoji", "cat", "https://example.com/cat.png"),
            CustomEmoji(" :cat: ", " https://example.com/cat.png ").toEmojiTag(),
        )
    }

    @Test
    fun shortcodeRegexAcceptsNip30CharactersOnly() {
        val codes = CustomEmojiCodeRegex.findAll(":a-b_1: :日本: :x y:").map { it.groupValues[1] }.toList()

        assertEquals(listOf("a-b_1"), codes)
    }

    @Test
    fun setAddressIsWrittenAndReadAsFourthElement() {
        val address = EmojiSetAddress("a".repeat(64), "cats")
        val cat = CustomEmoji("cat", "https://example.com/cat.png")

        val tag = cat.toEmojiTag(address)

        assertEquals(listOf("emoji", "cat", cat.imageUrl, address.value), tag)
        assertEquals(address, emojiTagSetAddress(tag))
        assertEquals(cat, parseEmojiTag(tag))
        assertEquals(
            listOf(tag),
            customEmojiTagsForContent(":cat:", listOf(cat)) { address },
        )
    }
}
