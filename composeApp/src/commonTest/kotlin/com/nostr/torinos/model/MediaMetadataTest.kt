package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaMetadataTest {
    @Test
    fun parsesNip92ImetaWithNip94Fields() {
        val media = parseImetaTags(
            listOf(
                listOf(
                    "imeta",
                    "url https://media.example/image",
                    "m image/jpeg",
                    "dim 1200x800",
                    "thumb https://media.example/thumb.jpg",
                    "alt 海辺の写真",
                    "fallback https://backup.example/image.jpg",
                ),
            ),
        ).single()

        assertEquals("https://media.example/image", media.url)
        assertEquals("image/jpeg", media.mimeType)
        assertEquals(1200, media.width)
        assertEquals(800, media.height)
        assertEquals("海辺の写真", media.alt)
        assertEquals(listOf("https://backup.example/image.jpg"), media.fallbackUrls)
        assertTrue(media.isImage)
    }

    @Test
    fun parsesKind1063FileMetadataEvent() {
        val event = NostrEvent(
            id = "id",
            pubkey = "pubkey",
            createdAt = 1,
            kind = NIP94_FILE_METADATA_KIND,
            tags = listOf(
                listOf("url", "https://media.example/file.webp"),
                listOf("m", "image/webp"),
                listOf("x", "abc123"),
            ),
            content = "画像の説明",
            sig = "sig",
        )

        val media = parseNip94Event(event)
        assertEquals("https://media.example/file.webp", media?.url)
        assertEquals("abc123", media?.sha256)
    }

    @Test
    fun createsProviderThumbnailsWithoutChangingOriginalUrl() {
        val imgur = MediaMetadata(url = "https://i.imgur.com/AbC123.png")
        val blossom = MediaMetadata(url = "https://alice.blossom.band/hash.jpg")

        assertEquals("https://i.imgur.com/AbC123m.png", imgur.timelinePreviewUrl())
        assertEquals("https://alice.blossom.band/hash.jpg?w=360", blossom.timelinePreviewUrl(360))
        assertEquals("https://i.imgur.com/AbC123.png", imgur.url)
        assertFalse(MediaMetadata(url = "https://example.com/file.bin").isImage)
    }

    @Test
    fun explicitThumbnailTakesPriorityOverProviderRule() {
        val media = MediaMetadata(
            url = "https://i.imgur.com/AbC123.png",
            thumbnailUrl = "https://cdn.example/thumb.webp",
        )

        assertEquals("https://cdn.example/thumb.webp", media.timelinePreviewUrl())
    }
}
