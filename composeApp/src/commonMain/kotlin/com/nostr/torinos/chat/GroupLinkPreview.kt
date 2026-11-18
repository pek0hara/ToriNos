package com.nostr.torinos.chat

import com.nostr.torinos.model.extractWebUrlMatches
import com.nostr.torinos.model.extractWebUrls
import com.nostr.torinos.model.isImageUrl

/** 文章の中のむき出しの URL を、押せるリンクに分ける(Markdown の `[文字](URL)` と同じ扱いにする)。 */
fun List<MdInline>.withAutolinks(): List<MdInline> = flatMap { inline ->
    if (inline !is MdInline.Text) return@flatMap listOf(inline)
    val matches = extractWebUrlMatches(inline.text)
    if (matches.isEmpty()) return@flatMap listOf(inline)
    buildList {
        var cursor = 0
        matches.forEach { match ->
            if (match.start > cursor) add(MdInline.Text(inline.text.substring(cursor, match.start)))
            add(MdInline.Link(match.url, match.url))
            cursor = match.endExclusive
        }
        if (cursor < inline.text.length) add(MdInline.Text(inline.text.substring(cursor)))
    }
}

/**
 * メッセージの下にプレビューを出すリンク。本文の最初の Web のリンク(画像・動画・`buzz://` などは除く)。
 * Buzz は Markdown として読み、`[文字](URL)` とむき出しの URL のどちらも対象にする。標準の NIP-29 は文章から探す。
 */
fun groupLinkPreviewUrl(body: String, flavor: GroupFlavor): String? {
    val candidates = if (flavor == GroupFlavor.Buzz) {
        parseMarkdownLite(body).flatMap { block ->
            val inlines = when (block) {
                is MdBlock.Paragraph -> block.inlines
                is MdBlock.Heading -> block.inlines
                is MdBlock.Quote -> block.inlines
                else -> emptyList()
            }
            inlines.withAutolinks().filterIsInstance<MdInline.Link>().map { it.url }
        }
    } else {
        extractWebUrls(body)
    }
    return candidates.firstOrNull { url ->
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        (scheme == "http" || scheme == "https") && !isImageUrl(url) && !isVideoLink(url)
    }
}

private fun isVideoLink(url: String): Boolean =
    url.substringBefore('?').substringBefore('#').lowercase().let { it.endsWith(".mp4") || it.endsWith(".mov") || it.endsWith(".webm") }
