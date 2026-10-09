package com.nostr.torinos.ui.post

import com.nostr.torinos.emoji.CustomEmoji
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposeNoteContentTest {
    @Test fun quoteOnlyContentPublishesReferencesAndTagsInOrderWithoutPreviewText() {
        val quotes = listOf("22", "11").map { prefix ->
            val id = prefix.repeat(32)
            PostQuote(id, "nostr:" + com.nostr.torinos.model.encodeNevent(id, listOf("wss://relay.example"), "aa".repeat(32)), "引用の本文")
        }
        val composed = composeNoteContent("", emptyList(), emptyList(), quotes = quotes + quotes.first())!!
        assertEquals(quotes.joinToString("\n\n") { it.uri }, composed.content)
        assertEquals(quotes.map { it.eventId }, composed.tags.filter { it.first() == "q" }.map { it[1] })
        assertTrue(composed.tags.any { it == listOf("p", "aa".repeat(32)) })
        assertTrue(composed.tags.none { it.first() == "t" })
        assertTrue(!composed.content.contains("引用の本文"))
    }

    @Test fun aQuoteReferenceAlreadyInTheBodyIsNotAppendedAgain() {
        val id = "11".repeat(32)
        val quote = PostQuote(id, "nostr:${com.nostr.torinos.model.encodeNevent(id)}", "preview")
        val composed = composeNoteContent("紹介\n${quote.uri}", emptyList(), emptyList(), quotes = listOf(quote))!!
        assertEquals("紹介\n${quote.uri}", composed.content)
        assertEquals(1, composed.tags.count { it.first() == "q" })
    }

    @Test
    fun blankTextWithoutAttachmentsIsNothingToPost() {
        assertNull(composeNoteContent("  \n ", emptyList(), emptyList()))
    }

    @Test
    fun uploadedImagesAreAppendedAndPendingOnesIgnored() {
        val images = listOf(
            ImageAttachment(id = 0, previewBytes = null, uploadedUrl = "https://img/a.png", isUploading = false),
            ImageAttachment(id = 1, previewBytes = null, uploadedUrl = null, isUploading = true),
        )
        val composed = composeNoteContent(" hello ", images, emptyList())!!
        assertEquals("hello\nhttps://img/a.png", composed.content)
    }

    @Test
    fun customEmojiTagsAreDerivedFromTheFinalContent() {
        val composed = composeNoteContent(
            "hi :wave:",
            emptyList(),
            listOf(CustomEmoji("wave", "https://e/wave.png"), CustomEmoji("unused", "https://e/u.png")),
        )!!
        assertTrue(listOf("emoji", "wave", "https://e/wave.png") in composed.tags)
        assertTrue(composed.tags.none { it.getOrNull(1) == "unused" })
        // client タグや返信タグは呼び出し側が付ける。
        assertTrue(composed.tags.none { it.firstOrNull() == "client" || it.firstOrNull() == "e" })
    }

    @Test fun anArticleQuotePublishesItsNaddrWithTheAddressInTheQTag() {
        val author = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        val quote = articleQuote(author, "torinos-abc", " 記事タイトル ", "wss://yabu.me")
        assertEquals("30023:$author:torinos-abc", quote.eventId)
        assertEquals("記事「記事タイトル」", quote.displayPreview)

        val composed = composeNoteContent("読んで", emptyList(), emptyList(), quotes = listOf(quote))!!

        assertEquals("読んで\n\n${quote.uri}", composed.content)
        assertEquals(listOf("q", "30023:$author:torinos-abc", "wss://yabu.me"), composed.tags.single { it.first() == "q" })
        assertTrue(composed.tags.any { it == listOf("p", author) })
    }

    @Test fun anArticleReferenceAlreadyInTheBodyIsNotAppendedAgain() {
        val quote = articleQuote("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", "x", "t", null)
        val composed = composeNoteContent("紹介 ${quote.uri}", emptyList(), emptyList(), quotes = listOf(quote))!!
        assertEquals("紹介 ${quote.uri}", composed.content)
    }
}
