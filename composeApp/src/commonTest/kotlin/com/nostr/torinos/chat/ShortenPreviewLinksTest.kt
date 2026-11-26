package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ShortenPreviewLinksTest {
    @Test
    fun replacesUrlsWithTheirDomain() {
        val result = shortenPreviewLinks("これ貼れば？ https://amzn.asia/d/03abc と https://www.publickey1.jp/blog/1.html")
        assertEquals("これ貼れば？ amzn.asia と publickey1.jp", result.text)
        assertEquals(listOf(7..15, 19..31), result.links)
        assertEquals("amzn.asia", result.text.substring(result.links[0]))
        assertEquals("publickey1.jp", result.text.substring(result.links[1]))
    }

    @Test
    fun dropsPortUserAndTrailingPunctuation() {
        assertEquals("見て example.com", shortenPreviewLinks("見て https://user@example.com:8080/x").text)
        assertEquals("（example.com）", shortenPreviewLinks("（https://example.com）").text)
    }

    @Test
    fun leavesTextWithoutUrlsAlone() {
        val result = shortenPreviewLinks("寒くて何もやる気が起きないな")
        assertEquals("寒くて何もやる気が起きないな", result.text)
        assertEquals(emptyList(), result.links)
    }
}
