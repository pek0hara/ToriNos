package com.nostr.torinos.ui.components

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventUrlExtractionTest {
    @Test
    fun collectsUrlsFromContentThenTagsWithoutDuplicates() {
        val event = event(
            content = "見て https://media.example/clip.mp4 と https://example.com/a.",
            tags = listOf(
                listOf("imeta", "url https://media.example/clip.mp4", "image https://media.example/poster.jpg"),
                listOf("r", "https://example.com/b"),
            ),
        )

        assertEquals(
            listOf(
                "https://media.example/clip.mp4",
                "https://example.com/a",
                "https://media.example/poster.jpg",
                "https://example.com/b",
            ),
            extractEventUrls(event),
        )
    }

    @Test
    fun ignoresNonWebUrls() {
        val event = event(
            content = "nostr:note1abc",
            tags = listOf(listOf("e", "abc", "wss://relay.example"), listOf("p", "pubkey")),
        )

        assertTrue(extractEventUrls(event).isEmpty())
    }

    private fun event(content: String, tags: List<List<String>>) = NostrEvent(
        id = "id",
        pubkey = "pubkey",
        createdAt = 1,
        kind = 1,
        tags = tags,
        content = content,
        sig = "sig",
    )
}
