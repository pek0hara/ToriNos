package com.nostr.torinos.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlExtractionTest {
    @Test
    fun truncateTextPreservingWebUrls_extendsCutInsideUrlToFullUrl() {
        val url = "https://example.com/articles/very-long-slug"
        val text = "Read this $url for details"

        assertEquals(
            "Read this $url…",
            truncateTextPreservingWebUrls(text, "Read this https://example.com".length),
        )
    }

    @Test
    fun truncateTextPreservingWebUrls_cutsNormallyOutsideUrl() {
        val text = "Read this https://example.com and then continue"

        assertEquals(
            "Read this https://example.com and…",
            truncateTextPreservingWebUrls(text, "Read this https://example.com and".length),
        )
    }

    @Test
    fun truncateTextPreservingWebUrls_doesNotAddEllipsisAtLimit() {
        val text = "Read this https://example.com"

        assertEquals(text, truncateTextPreservingWebUrls(text, text.length))
    }

    @Test
    fun extractClickableUriMatches_includesSpotifySearchUriWithSpaces() {
        val text = "now playing spotify:search:King Gnu"

        assertEquals(
            listOf(
                ExtractedClickableUri(
                    uri = "spotify:search:King%20Gnu",
                    text = "spotify:search:King Gnu",
                    start = "now playing ".length,
                    endExclusive = text.length,
                ),
            ),
            extractClickableUriMatches(text),
        )
    }

    @Test
    fun extractClickableUriMatches_encodesSpotifySearchQueryAsUtf8() {
        assertEquals(
            "spotify:search:%E6%98%9F%E9%87%8E%E6%BA%90",
            extractClickableUriMatches("spotify:search:星野源").single().uri,
        )
    }

    @Test
    fun extractWebUrls_excludesSpotifySearchUri() {
        assertEquals(emptyList(), extractWebUrls("spotify:search:King Gnu"))
    }
}
