package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlDisplayTest {
    @Test
    fun removesSchemeAndDecodesJapanesePath() {
        assertEquals(
            "ja.wikipedia.org/wiki/チカ",
            shortenUrlForDisplay("https://ja.wikipedia.org/wiki/%E3%83%81%E3%82%AB"),
        )
    }

    @Test
    fun removesWwwAndTrailingSlash() {
        assertEquals("example.com", shortenUrlForDisplay("http://www.example.com/"))
        assertEquals("example.com/a", shortenUrlForDisplay("HTTPS://example.com/a/"))
    }

    @Test
    fun keepsEncodedAsciiCharacters() {
        // %2F や %20 を戻すと別のURLに見えるので、そのまま残す。
        assertEquals(
            "example.com/a%2Fb?q=x%20y",
            shortenUrlForDisplay("https://example.com/a%2Fb?q=x%20y"),
        )
    }

    @Test
    fun keepsInvalidUtf8SequenceAsIs() {
        assertEquals("example.com/%E3%83", shortenUrlForDisplay("https://example.com/%E3%83"))
        assertEquals("example.com/%FFabc", shortenUrlForDisplay("https://example.com/%FFabc"))
    }

    @Test
    fun truncatesLongUrlWithEllipsis() {
        val shortened = shortenUrlForDisplay("https://example.com/" + "a".repeat(100), maxLength = 30)
        assertEquals(30, shortened.length)
        assertEquals("example.com/" + "a".repeat(17) + "…", shortened)
    }

    @Test
    fun doesNotSplitSurrogatePairWhenTruncating() {
        // 19文字目が絵文字の上位サロゲートになる長さ。
        val shortened = shortenUrlForDisplay("https://example.com/abcdef😀😀", maxLength = 20)
        assertEquals("example.com/abcdef…", shortened)
    }

    @Test
    fun leavesNonHttpTextUntouched() {
        assertEquals("spotify:search:foo bar", shortenUrlForDisplay("spotify:search:foo bar"))
    }

    @Test
    fun neverTruncatesInsideHost() {
        // ホスト名の途中で切ると、別のサイトに見せかけられる。
        val host = "accounts.example.com.security-check.login.evil.example"
        assertEquals("$host/…", shortenUrlForDisplay("https://$host/path/to/page"))
        assertEquals(host, shortenUrlForDisplay("https://$host"))
    }

    @Test
    fun doesNotDecodeInvisibleOrDirectionControlCharacters() {
        // U+202E（文字の並びを逆にする）や U+200B（幅ゼロ）を戻すと、表示を偽装できる。
        assertEquals("example.com/%E2%80%AEgpj.exe", shortenUrlForDisplay("https://example.com/%E2%80%AEgpj.exe"))
        assertEquals("example.com/a%E2%80%8Bb", shortenUrlForDisplay("https://example.com/a%E2%80%8Bb"))
        assertEquals("example.com/a%E3%80%80b", shortenUrlForDisplay("https://example.com/a%E3%80%80b"))
    }

    @Test
    fun doesNotDecodeInsideHost() {
        assertEquals("%E3%81%82.example.com/あ", shortenUrlForDisplay("https://%E3%81%82.example.com/%E3%81%82"))
    }

    @Test
    fun decodesEmojiInPath() {
        assertEquals("example.com/😀", shortenUrlForDisplay("https://example.com/%F0%9F%98%80"))
    }
}
