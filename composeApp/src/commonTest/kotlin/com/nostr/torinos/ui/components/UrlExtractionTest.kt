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
}
