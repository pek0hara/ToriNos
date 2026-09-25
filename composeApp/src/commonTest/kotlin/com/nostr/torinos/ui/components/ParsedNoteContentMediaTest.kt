package com.nostr.torinos.ui.components

import com.nostr.torinos.model.NIP94_FILE_METADATA_KIND
import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsedNoteContentMediaTest {
    @Test
    fun mp4UrlBecomesPlayableMediaInsteadOfLinkPreview() {
        val parsed = parseNoteContent(note("見て https://media.example/clip.mp4"))

        assertEquals(listOf("https://media.example/clip.mp4"), parsed.playableMedia.map { it.url })
        assertNull(parsed.linkPreviewUrl)
        assertEquals("見て", parsed.textContent)
        assertTrue(parsed.images.isEmpty())
    }

    @Test
    fun trailingPunctuationIsNotPartOfMediaUrl() {
        val parsed = parseNoteContent(note("(https://media.example/clip.mp4)"))

        assertEquals(listOf("https://media.example/clip.mp4"), parsed.playableMedia.map { it.url })
    }

    @Test
    fun ordinaryUrlAfterMediaStillGetsLinkPreview() {
        val parsed = parseNoteContent(
            note("https://media.example/song.mp3\nhttps://example.com/article"),
        )

        assertEquals(listOf("https://media.example/song.mp3"), parsed.playableMedia.map { it.url })
        assertEquals("https://example.com/article", parsed.linkPreviewUrl)
        assertEquals("https://example.com/article", parsed.textContent)
    }

    @Test
    fun imagesAndVideosAreSeparated() {
        val parsed = parseNoteContent(
            note("https://media.example/a.png https://media.example/b.mov"),
        )

        assertEquals(listOf("https://media.example/a.png"), parsed.imageUrls)
        assertEquals(listOf("https://media.example/b.mov"), parsed.playableMedia.map { it.url })
        assertEquals("", parsed.textContent)
    }

    @Test
    fun imetaVideoWithoutExtensionIsPlayableAndKeepsMetadata() {
        val url = "https://blossom.example/0123abcd"
        val parsed = parseNoteContent(
            note(
                content = "動画 $url",
                tags = listOf(
                    listOf(
                        "imeta",
                        "url $url",
                        "m video/mp4",
                        "dim 1080x1920",
                        "image https://blossom.example/poster.jpg",
                    ),
                ),
            ),
        )

        val media = parsed.playableMedia.single()
        assertEquals(url, media.url)
        assertEquals(1080, media.width)
        assertEquals(1920, media.height)
        assertEquals("https://blossom.example/poster.jpg", media.posterUrl)
        assertNull(parsed.linkPreviewUrl)
        assertEquals("動画", parsed.textContent)
    }

    @Test
    fun duplicatedMediaUrlIsShownOnce() {
        val url = "https://media.example/clip.mp4"
        val parsed = parseNoteContent(
            note(
                content = "$url $url",
                tags = listOf(listOf("imeta", "url $url", "m video/mp4")),
            ),
        )

        assertEquals(listOf(url), parsed.playableMedia.map { it.url })
        assertEquals("video/mp4", parsed.playableMedia.single().mimeType)
    }

    @Test
    fun imetaNotReferencedByContentIsIgnored() {
        val parsed = parseNoteContent(
            note(
                content = "本文だけ",
                tags = listOf(listOf("imeta", "url https://media.example/other.mp4", "m video/mp4")),
            ),
        )

        assertTrue(parsed.playableMedia.isEmpty())
    }

    @Test
    fun mediaOrderFollowsContent() {
        val parsed = parseNoteContent(
            note("https://media.example/2.mp3 https://media.example/1.mp4"),
        )

        assertEquals(
            listOf("https://media.example/2.mp3", "https://media.example/1.mp4"),
            parsed.playableMedia.map { it.url },
        )
    }

    @Test
    fun nip94VideoEventIsPlayable() {
        val parsed = parseNoteContent(
            NostrEvent(
                id = "id",
                pubkey = "pubkey",
                createdAt = 1,
                kind = NIP94_FILE_METADATA_KIND,
                tags = listOf(
                    listOf("url", "https://media.example/file"),
                    listOf("m", "video/webm"),
                ),
                content = "動画の説明",
                sig = "sig",
            ),
        )

        assertEquals(listOf("https://media.example/file"), parsed.playableMedia.map { it.url })
        assertTrue(parsed.images.isEmpty())
    }

    private fun note(content: String, tags: List<List<String>> = emptyList()) = NostrEvent(
        id = "id",
        pubkey = "pubkey",
        createdAt = 1,
        kind = 1,
        tags = tags,
        content = content,
        sig = "sig",
    )
}
