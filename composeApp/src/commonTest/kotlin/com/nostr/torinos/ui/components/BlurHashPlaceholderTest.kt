package com.nostr.torinos.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BlurHashPlaceholderTest {
    @Test
    fun decodesValidBlurHashToRequestedGrid() {
        val decoded = decodeBlurHash("LEHV6nWB2yk8pyo0adR*.7kCMdnj", width = 8, height = 6)

        assertNotNull(decoded)
        assertEquals(8, decoded.width)
        assertEquals(6, decoded.height)
        assertEquals(48, decoded.colors.size)
    }

    @Test
    fun rejectsMalformedBlurHash() {
        assertNull(decodeBlurHash("not-a-blurhash"))
        assertNull(decodeBlurHash("LEHV6nWB2yk8pyo0adR*.7kCMdnj", width = 0))
    }
}
