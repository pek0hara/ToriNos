package com.nostr.torinos.article

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.extractNostrEventReferences
import com.nostr.torinos.ui.post.*
import kotlin.test.*

class MatometrQuoteInputTest {
    private fun post(number: Int, content: String = "投稿$number"): SelectedMatometrPost {
        val id = number.toString(16).padStart(64, '0')
        val author = "aa".repeat(32)
        return SelectedMatometrPost(NostrEvent(id, author, 1, 1, emptyList(), content, ""),
            MatometrReference(id, author, listOf("wss://relay.example")))
    }
    private fun memo(text: String = "本文") = PostMemoData(text, emptyList(), null, null, 1, null, 1)

    @Test fun newQuotePostKeepsTheBodyEmptyAndSelectionOrderInPreviews() {
        val posts = listOf(post(2), post(1))
        val result = buildMatometrQuoteMemo(null, posts)
        assertEquals("", result.text)
        assertEquals(posts.map { it.event.id }, result.quotes.map { it.eventId })
        assertEquals(listOf("投稿2", "投稿1"), result.quotes.map { it.displayPreview })
        assertEquals(posts.map { it.event.id }, extractNostrEventReferences(composeQuotedText(result.text, result.quotes)).map { it.eventId })
        assertEquals(1, result.noteKind)
        assertNull(result.replyToId)
    }

    @Test fun addingQuotesKeepsTheReplyRelayImagesCursorAndExistingQuote() {
        val first = post(1)
        val base = memo().copy(noteKind = 42, channelId = "channel", replyToId = "parent", replyToPubkey = "author",
            imageUrls = listOf("https://image.example/a.jpg"), selectedRelayUrls = setOf("wss://relay.example"), cursorStart = 2,
            quoteEventId = first.event.id, quotePubkey = first.event.pubkey, quotePreview = first.event.content)
        val result = buildMatometrQuoteMemo(base, listOf(first, post(2), post(2)))
        assertEquals(base.copy(quoteEventId = null, quotePubkey = null, quotePreview = null, quotes = result.quotes), result)
        assertEquals(listOf(first.event.id, post(2).event.id), result.quotes.map { it.eventId })
    }

    @Test fun referencesAlreadyTypedInTheBodyAreNotInsertedAgain() {
        val first = post(1)
        val result = buildMatometrQuoteMemo(memo("紹介\n${first.reference.uri}"), listOf(first, post(2)))
        assertEquals(listOf(post(2).event.id), result.quotes.map { it.eventId })
        assertEquals(listOf(first.event.id, post(2).event.id), extractNostrEventReferences(composeQuotedText(result.text, result.quotes)).map { it.eventId })
    }

    @Test fun quoteReferencesCountTowardsTheLimitWithoutDiscardingAnyQuotes() {
        val quotes = listOf(post(1))
        val suffixLength = 2 + quotes.single().reference.uri.length
        assertEquals(MAX_POST_CHARS, composeQuotedText(buildMatometrQuoteMemo(memo("あ".repeat(MAX_POST_CHARS - suffixLength)), quotes).text,
            buildMatometrQuoteMemo(null, quotes).quotes).length)
        assertFailsWith<IllegalStateException> { buildMatometrQuoteMemo(memo("あ".repeat(MAX_POST_CHARS - suffixLength + 1)), quotes) }
        assertFailsWith<IllegalStateException> { buildMatometrQuoteMemo(memo(" ".repeat(MAX_POST_CHARS + 1)), quotes) }
    }

    @Test fun previewStorageIsBoundedAndEmptyContentHasAFallback() {
        val result = buildMatometrQuoteMemo(null, listOf(post(1, "あ".repeat(10_000)), post(2, "")))
        assertEquals(MAX_QUOTE_PREVIEW_CHARS, result.quotes.first().preview.length)
        assertEquals("投稿 ${post(2).event.id.take(8)}", result.quotes.last().displayPreview)
    }
}
