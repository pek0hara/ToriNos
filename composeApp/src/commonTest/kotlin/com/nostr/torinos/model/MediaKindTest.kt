package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaKindTest {
    @Test
    fun classifiesByExtension() {
        assertEquals(MediaKind.Image, MediaMetadata(url = "https://media.example/a.png").mediaKind)
        assertEquals(MediaKind.Video, MediaMetadata(url = "https://media.example/a.mp4").mediaKind)
        assertEquals(MediaKind.Video, MediaMetadata(url = "https://media.example/a.MOV").mediaKind)
        assertEquals(MediaKind.Video, MediaMetadata(url = "https://media.example/a.webm").mediaKind)
        assertEquals(MediaKind.Audio, MediaMetadata(url = "https://media.example/a.mp3").mediaKind)
        assertEquals(MediaKind.Audio, MediaMetadata(url = "https://media.example/a.m4a").mediaKind)
    }

    @Test
    fun ignoresQueryAndFragmentWhenClassifying() {
        assertEquals(MediaKind.Video, MediaMetadata(url = "https://media.example/a.mp4?token=1").mediaKind)
        assertEquals(MediaKind.Video, MediaMetadata(url = "https://media.example/a.mp4#t=10").mediaKind)
        assertNull(MediaMetadata(url = "https://media.example/watch?file=a.mp4").mediaKind)
    }

    @Test
    fun mimeTypeTakesPriorityOverExtension() {
        assertEquals(
            MediaKind.Video,
            MediaMetadata(url = "https://media.example/abc123", mimeType = "video/mp4").mediaKind,
        )
        assertEquals(
            MediaKind.Audio,
            MediaMetadata(url = "https://media.example/abc.mp4", mimeType = "Audio/MP4").mediaKind,
        )
        assertEquals(
            MediaKind.Video,
            MediaMetadata(url = "https://media.example/poster.jpg", mimeType = "video/mp4").mediaKind,
        )
    }

    @Test
    fun fallsBackToExtensionForNonMediaMimeType() {
        assertEquals(
            MediaKind.Image,
            MediaMetadata(url = "https://media.example/a.jpg", mimeType = "application/octet-stream").mediaKind,
        )
    }

    @Test
    fun unknownFilesAreNotPlayable() {
        val media = MediaMetadata(url = "https://media.example/file.bin")
        assertNull(media.mediaKind)
        assertFalse(media.isPlayable)
        assertFalse(media.isImage)
    }

    @Test
    fun playableMeansVideoOrAudio() {
        assertTrue(MediaMetadata(url = "https://media.example/a.mp4").isPlayable)
        assertTrue(MediaMetadata(url = "https://media.example/a.mp3").isPlayable)
        assertFalse(MediaMetadata(url = "https://media.example/a.png").isPlayable)
        assertFalse(MediaMetadata(url = "https://media.example/a.mp4").isImage)
    }

    @Test
    fun posterPrefersPreviewImageThenThumbnail() {
        assertEquals(
            "https://media.example/image.jpg",
            MediaMetadata(
                url = "https://media.example/a.mp4",
                previewUrl = "https://media.example/image.jpg",
                thumbnailUrl = "https://media.example/thumb.jpg",
            ).posterUrl,
        )
        assertEquals(
            "https://media.example/thumb.jpg",
            MediaMetadata(url = "https://media.example/a.mp4", thumbnailUrl = "https://media.example/thumb.jpg").posterUrl,
        )
        assertNull(MediaMetadata(url = "https://media.example/a.mp4", thumbnailUrl = "not a url").posterUrl)
    }
}
