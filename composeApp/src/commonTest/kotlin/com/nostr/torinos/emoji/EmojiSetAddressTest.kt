package com.nostr.torinos.emoji

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmojiSetAddressTest {
    private val author = "ab".repeat(32)

    @Test
    fun parsesAndFormatsAddress() {
        val address = EmojiSetAddress.parse("30030:$author:cats:v2")

        assertEquals(EmojiSetAddress(author, "cats:v2"), address)
        assertEquals("30030:$author:cats:v2", address?.value)
    }

    @Test
    fun normalizesUppercasePubkey() {
        assertEquals(EmojiSetAddress(author, "cats"), EmojiSetAddress.parse("30030:${author.uppercase()}:cats"))
    }

    @Test
    fun rejectsOtherKindsShortPubkeysAndBlankIdentifier() {
        assertNull(EmojiSetAddress.parse("30023:$author:cats"))
        assertNull(EmojiSetAddress.parse("30030:abc:cats"))
        assertNull(EmojiSetAddress.parse("30030:${"z".repeat(64)}:cats"))
        assertNull(EmojiSetAddress.parse("30030:$author:"))
    }
}
