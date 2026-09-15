package com.nostr.torinos.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XPostEmbedTest {
    @Test
    fun extractsPostIdsFromSupportedXUrls() {
        assertEquals("123456", extractXPostId("https://x.com/user/status/123456"))
        assertEquals("987654", extractXPostId("https://www.twitter.com/user/status/987654?s=20"))
        assertEquals("42", extractXPostId("http://mobile.twitter.com/user/status/42/photo/1"))
    }

    @Test
    fun rejectsNonPostAndLookalikeUrls() {
        assertNull(extractXPostId("https://x.com/user"))
        assertNull(extractXPostId("https://example.com/user/status/123456"))
        assertNull(extractXPostId("https://notx.com/user/status/123456"))
        assertNull(extractXPostId("https://x.com/user/status/not-a-number"))
    }

    @Test
    fun buildsPrivacyPreservingEmbedUrl() {
        assertEquals(
            "https://platform.twitter.com/embed/Tweet.html?id=123&dnt=true&theme=dark",
            xPostEmbedUrl(postId = "123", darkTheme = true),
        )
    }
}
