package com.nostr.torinos.model

/**
 * 本文の中の押せるリンク。フィードの本文（[com.nostr.torinos.ui.components.LinkedText]）と
 * Buzz の Markdown の本文（[com.nostr.torinos.chat.withAutolinks]）で同じものを拾い、同じ文字で出す。
 */
sealed class TextLink(val start: Int, val endExclusive: Int) {
    /** Web の URL など。[text] は本文の文字、[uri] は開く先。 */
    class Uri(start: Int, endExclusive: Int, val uri: String, val text: String) : TextLink(start, endExclusive)

    /** 人へのリンク（`nostr:npub1…` / `npub1…`）。 */
    class Profile(start: Int, endExclusive: Int, val pubkey: String, val npub: String) : TextLink(start, endExclusive)

    class Hashtag(start: Int, endExclusive: Int, val tag: String) : TextLink(start, endExclusive)

    /** グループへのリンク（kind 39000 の naddr、`buzz://message`・`buzz://channel`）。 */
    class Group(start: Int, endExclusive: Int, val uri: String) : TextLink(start, endExclusive)
}

private val hashtagTextRegex = Regex("""(?<![\p{L}\p{N}_])#[\p{L}\p{N}_]+""")

/**
 * 本文の中のリンクを、重ならないように出現順で返す。
 * グループへのリンクを先に取り、残りは先に始まるもの（同じ位置なら長いもの）を取る。
 */
fun findTextLinks(
    text: String,
    web: Boolean = true,
    profiles: Boolean = true,
    groups: Boolean = true,
    hashtags: Boolean = false,
): List<TextLink> {
    val groupLinks = if (groups) {
        com.nostr.torinos.chat.extractGroupLinks(text).map { TextLink.Group(it.start, it.endExclusive, it.uri) }
    } else {
        emptyList()
    }
    val others = buildList {
        if (web) {
            extractClickableUriMatches(text).forEach { add(TextLink.Uri(it.start, it.endExclusive, it.uri, it.text)) }
        }
        if (profiles) {
            extractNpubReferences(text).forEach { add(TextLink.Profile(it.start, it.endExclusive, it.pubkey, it.npub.lowercase())) }
        }
        if (hashtags) {
            hashtagTextRegex.findAll(text).forEach { add(TextLink.Hashtag(it.range.first, it.range.last + 1, it.value.removePrefix("#"))) }
        }
    }
    val kept = groupLinks.toMutableList<TextLink>()
    others.sortedWith(compareBy<TextLink> { it.start }.thenByDescending { it.endExclusive }).forEach { link ->
        if (kept.none { link.start < it.endExclusive && link.endExclusive > it.start }) kept += link
    }
    return kept.sortedBy { it.start }
}

/**
 * グループへのリンクを本文に出す文字。Buzz のチャンネルへのリンクは、名前が分かれば `#名前`。
 * [channelName] はチャンネル ID から名前を引く（同じコミュニティの中だけで分かる）。
 */
fun groupLinkLabel(uri: String, channelName: (String) -> String? = { null }): String {
    if (!uri.startsWith("buzz://", ignoreCase = true)) return "💬 グループへのリンク"
    val channel = com.nostr.torinos.chat.parseBuzzLink(uri) as? com.nostr.torinos.chat.BuzzLink.Channel
    val name = channel?.let { channelName(it.channelId) }?.takeIf { it.isNotBlank() }
    return if (name != null) "#$name" else "💬 Buzz のメッセージ"
}

/** 人へのリンクを本文に出す文字。名前が分からなければ pubkey を短くして出す。 */
fun profileLinkLabel(pubkey: String, name: String?): String =
    "@" + (name?.takeIf { it.isNotBlank() } ?: (pubkey.take(8) + "…" + pubkey.takeLast(8)))

/** グループへのリンク（[findTextLinks] が [TextLink.Group] にするもの）か。 */
fun isGroupLinkUri(uri: String): Boolean =
    com.nostr.torinos.chat.extractGroupLinks(uri).singleOrNull()?.let { it.start == 0 && it.endExclusive == uri.length } == true
