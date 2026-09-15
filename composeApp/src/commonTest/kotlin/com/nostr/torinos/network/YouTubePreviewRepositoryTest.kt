package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubePreviewRepositoryTest {
    @Test
    fun parsesTitleFromOEmbedResponse() {
        assertEquals(
            "動画タイトル",
            parseYouTubeOEmbedTitle("""{"title":"動画タイトル","author_name":"投稿者"}"""),
        )
    }

    @Test
    fun rejectsMissingBlankAndInvalidTitles() {
        assertNull(parseYouTubeOEmbedTitle("{}"))
        assertNull(parseYouTubeOEmbedTitle("""{"title":"  "}"""))
        assertNull(parseYouTubeOEmbedTitle("not-json"))
    }
}
