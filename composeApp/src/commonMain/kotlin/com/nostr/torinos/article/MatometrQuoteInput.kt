package com.nostr.torinos.article

import com.nostr.torinos.model.extractNostrEventReferences
import com.nostr.torinos.ui.post.MAX_POST_CHARS
import com.nostr.torinos.ui.post.MAX_QUOTE_PREVIEW_CHARS
import com.nostr.torinos.ui.post.PostMemoData
import com.nostr.torinos.ui.post.PostQuote
import com.nostr.torinos.ui.post.composeQuotedText
import com.nostr.torinos.ui.post.quoteInputs
import kotlin.time.Clock

internal fun buildMatometrQuoteMemo(base: PostMemoData?, posts: List<SelectedMatometrPost>): PostMemoData {
    val memo = base ?: PostMemoData("", emptyList(), null, null, 1, null, Clock.System.now().epochSeconds)
    val existingIds = extractNostrEventReferences(memo.text).map { it.eventId }.toSet()
    val quotes = (memo.quoteInputs() + posts.filterNot { it.event.id in existingIds }.map {
        PostQuote(it.event.id, it.reference.uri, it.event.content.take(MAX_QUOTE_PREVIEW_CHARS))
    }).distinctBy { it.eventId }
    check(memo.text.length <= MAX_POST_CHARS && composeQuotedText(memo.text, quotes).length <= MAX_POST_CHARS) {
        "参照追加後の本文が${MAX_POST_CHARS}文字を超えます。記事作成をご利用ください"
    }
    return memo.copy(quotes = quotes, quoteEventId = null, quotePubkey = null, quotePreview = null)
}
