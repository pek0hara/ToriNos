package com.nostr.torinos.ui.post

import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import com.nostr.torinos.model.encodeNaddr
import com.nostr.torinos.model.encodeNevent
import com.nostr.torinos.model.extractNostrAddressReferences
import com.nostr.torinos.model.extractNostrEventReferences
import kotlinx.serialization.Serializable

internal const val MAX_QUOTE_PREVIEW_CHARS = 500

/**
 * The preview is editor data; only the URI is included in the published note.
 * [eventId] is the key of the quote: an event id, or `kind:pubkey:identifier` for an article quoted by naddr.
 */
@Serializable
data class PostQuote(val eventId: String, val uri: String, val preview: String) {
    val displayPreview: String get() = preview.ifBlank { "投稿 ${eventId.take(8)}" }
}

internal fun PostMemoData.quoteInputs(): List<PostQuote> =
    (listOfNotNull(quoteEventId?.let {
        PostQuote(it, "nostr:${encodeNevent(it, authorPubkey = quotePubkey)}", quotePreview.orEmpty().take(MAX_QUOTE_PREVIEW_CHARS))
    }) + quotes).distinctBy { it.eventId }

/** 記事を naddr で引用する。キーは記事のアドレスなので、版が変わっても同じ記事は1件にまとまる。 */
internal fun articleQuote(pubkey: String, identifier: String, title: String, relayHint: String?): PostQuote =
    PostQuote(
        eventId = "$NIP23_ARTICLE_KIND:$pubkey:$identifier",
        uri = "nostr:" + encodeNaddr(NIP23_ARTICLE_KIND, pubkey, identifier, listOfNotNull(relayHint)),
        preview = "記事「${title.trim()}」".take(MAX_QUOTE_PREVIEW_CHARS),
    )

internal fun composeQuotedText(text: String, quotes: List<PostQuote>): String {
    val existing = extractNostrEventReferences(text).map { it.eventId }.toSet() +
        extractNostrAddressReferences(text).map { it.address }
    val uris = quotes.distinctBy { it.eventId }.filterNot { it.eventId in existing }.map { it.uri }
    return (listOf(text.trim()) + uris).filter { it.isNotBlank() }.joinToString("\n\n")
}
