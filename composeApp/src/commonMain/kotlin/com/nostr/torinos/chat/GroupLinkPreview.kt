package com.nostr.torinos.chat

import com.nostr.torinos.model.decodeNpub
import com.nostr.torinos.model.TextLink
import com.nostr.torinos.model.findTextLinks
import com.nostr.torinos.model.extractWebUrls
import com.nostr.torinos.model.isImageUrl

/**
 * 文章の中のむき出しのリンクを、押せるリンクに分ける(Markdown の `[文字](URL)` と同じ扱いにする)。
 * 拾うものはフィードの本文と同じ([findTextLinks]): Web の URL、グループへのリンク(`buzz://`・naddr)、人へのリンク。
 * 人へのリンクは URL を `nostr:npub1…` にそろえる([npubOfLink])。外へ開けない URL は文字のまま残す。
 */
fun List<MdInline>.withAutolinks(): List<MdInline> = flatMap { inline ->
    if (inline !is MdInline.Text) return@flatMap listOf(inline)
    val links = findTextLinks(inline.text).mapNotNull { link ->
        val url = when (link) {
            is TextLink.Uri -> link.uri.takeIf(::isSafeExternalLink)
            is TextLink.Group -> link.uri
            is TextLink.Profile -> "nostr:" + link.npub
            is TextLink.Hashtag -> null
        } ?: return@mapNotNull null
        Triple(link.start, link.endExclusive, url)
    }
    if (links.isEmpty()) return@flatMap listOf(inline)
    buildList {
        var cursor = 0
        links.forEach { (start, end, url) ->
            if (start > cursor) add(MdInline.Text(inline.text.substring(cursor, start)))
            add(MdInline.Link(inline.text.substring(start, end), url))
            cursor = end
        }
        if (cursor < inline.text.length) add(MdInline.Text(inline.text.substring(cursor)))
    }
}

/** 人へのリンク(`nostr:npub1…`)なら、その人の pubkey。 */
fun npubOfLink(url: String): String? {
    if (!url.startsWith("nostr:npub1", ignoreCase = true)) return null
    return decodeNpub(url.removePrefix("nostr:"))
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
