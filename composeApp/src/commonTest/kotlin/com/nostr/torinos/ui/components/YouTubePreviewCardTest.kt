package com.nostr.torinos.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubePreviewCardTest {
    @Test
    fun extractsVideoIdsFromSupportedYouTubeUrls() {
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ?t=42"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://youtube.com/live/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"))
    }

    @Test
    fun rejectsChannelsInvalidIdsAndLookalikeHosts() {
        assertNull(extractYouTubeVideoId("https://youtube.com/@openai"))
        assertNull(extractYouTubeVideoId("https://youtube.com/watch?v=too-short"))
        assertNull(extractYouTubeVideoId("https://notyoutube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(extractYouTubeVideoId("https://youtube.com.example.org/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun buildsYouTubeThumbnailUrl() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            youTubeThumbnailUrl("dQw4w9WgXcQ"),
        )
    }
}
