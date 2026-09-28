package com.nostr.torinos.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class InlineMediaPlayerTest {
    @Test
    fun aspectRatioDefaultsToWidescreen() {
        assertEquals(16f / 9f, inlineMediaAspectRatio(width = null, height = null))
        assertEquals(16f / 9f, inlineMediaAspectRatio(width = 1920, height = null))
        assertEquals(16f / 9f, inlineMediaAspectRatio(width = 0, height = 1080))
    }

    @Test
    fun aspectRatioUsesDimensionsWithinBounds() {
        assertEquals(4f / 3f, inlineMediaAspectRatio(width = 1440, height = 1080))
        assertEquals(9f / 16f, inlineMediaAspectRatio(width = 1080, height = 1920))
    }

    @Test
    fun aspectRatioIsClamped() {
        assertEquals(9f / 16f, inlineMediaAspectRatio(width = 100, height = 1000))
        assertEquals(2f, inlineMediaAspectRatio(width = 3000, height = 500))
    }

    @Test
    fun playerHeightUsesAspectRatioUntilMaximumHeight() {
        assertEquals(180.dp, inlineMediaHeight(320.dp, 16f / 9f, 360.dp))
        assertEquals(360.dp, inlineMediaHeight(320.dp, 9f / 16f, 360.dp))
    }

    @Test
    fun activatingAnotherPlayerReplacesTheCurrentOne() {
        val playback = MediaPlaybackCoordinator()

        playback.activate("a")
        playback.activate("b")

        assertEquals("b", playback.activeKey.value)
    }

    @Test
    fun deactivateOnlyStopsTheMatchingPlayer() {
        val playback = MediaPlaybackCoordinator()

        playback.activate("a")
        playback.activate("b")
        playback.deactivate("a")
        assertEquals("b", playback.activeKey.value)

        playback.deactivate("b")
        assertNull(playback.activeKey.value)
    }

    @Test
    fun playbackKeyDistinguishesSourceAndUrl() {
        val url = "https://media.example/a.mp4"
        assertEquals(mediaPlaybackKey("note1", url), mediaPlaybackKey("note1", url))
        assertNotEquals(mediaPlaybackKey("note1", url), mediaPlaybackKey("note2", url))
    }
}
