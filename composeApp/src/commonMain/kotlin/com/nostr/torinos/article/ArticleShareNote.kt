package com.nostr.torinos.article

import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import com.nostr.torinos.model.encodeNaddr

/** 記事を kind:1 のポストで共有するときの本文とタグ。 */
internal data class ArticleShareNote(val content: String, val tags: List<List<String>>)

/**
 * 記事を共有するポストを組み立てる。本文はひとこと（空ならタイトル）と記事への `nostr:naddr` 参照。
 * 参照先は NIP-18 の `q` タグにアドレス（`30023:作成者:識別子`）で入れる。
 */
internal fun buildArticleShareNote(
    comment: String,
    title: String,
    pubkey: String,
    identifier: String,
    relayHint: String?,
): ArticleShareNote {
    val naddr = encodeNaddr(NIP23_ARTICLE_KIND, pubkey, identifier, listOfNotNull(relayHint))
    val lead = comment.trim().ifEmpty { title.trim() }
    val content = (if (lead.isEmpty()) "" else "$lead\n\n") + "nostr:$naddr"
    val tags = listOf(
        listOf("q", "$NIP23_ARTICLE_KIND:$pubkey:$identifier", relayHint.orEmpty()),
        listOf("client", "ToriNos"),
    )
    return ArticleShareNote(content, tags)
}
