package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HiddenTextTest {
    @Test fun decodesFixedBytesFromTheSourceMapping() {
        // UTF-8 41 E3 81 82 = "Aあ"; each code point is E00F0 + byte.
        assertEquals(listOf("Aあ"), decodeHiddenTexts("🥟\uDB40\uDD31\uDB40\uDDD3\uDB40\uDD71\uDB40\uDD72"))
    }

    @Test fun decodesSourceFormatWithJapaneseEmojiAndNewlines() {
        assertEquals(listOf("秘密のメッセージ\n🥟\t次の行"), decodeHiddenTexts("本文🥟" + encode("秘密のメッセージ\n🥟\t次の行")))
    }

    @Test fun extractsMultipleRunsInOrderIncludingWithoutVisibleCarrier() {
        assertEquals(listOf("one", "two"), decodeHiddenTexts(encode("one") + " 🥟" + encode("two") + "本文"))
    }

    @Test fun preservesNestedContentForSeparateReveal() {
        val nested = "外側🥟" + encode("内側") + "末尾"
        val outer = decodeHiddenTexts("🥟" + encode(nested)).single()
        assertEquals(nested, outer)
        assertEquals(listOf("内側"), decodeHiddenTexts(outer))
    }

    @Test fun ignoresOrdinaryTextFlagsAndVariationSelectors() {
        assertTrue(decodeHiddenTexts("普通の投稿 🥟 ❤️ 👩‍💻 🇯🇵 🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC7F").isEmpty())
        assertTrue(decodeHiddenTexts("漢\uDB40\uDD00").isEmpty())
    }

    @Test fun ignoresInvalidUtf8AndUnpairedSurrogatesButContinuesScanning() {
        assertEquals(listOf("valid"), decodeHiddenTexts("\uDB40\uDDEF x" + encode("valid") + "\uDB40"))
        assertTrue(decodeHiddenTexts("\uDB40\uDC00\uDDEF").isEmpty())
    }

    @Test fun decodesNullAndFourByteUtf8() {
        val message = "\u0000\uD83F\uDFFF"
        assertEquals(listOf(message), decodeHiddenTexts(encode(message)))
    }

    private fun encode(text: String): String = buildString {
        text.encodeToByteArray().forEach { byte ->
            append('\uDB40')
            append((0xDCF0 + (byte.toInt() and 0xFF)).toChar())
        }
    }
}
