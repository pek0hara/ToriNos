package com.nostr.torinos.zap

import com.nostr.torinos.model.NostrEvent
import io.ktor.http.URLBuilder

const val ZAP_REQUEST_KIND = 9734
const val ZAP_RECEIPT_KIND = 9735
internal const val MAX_ZAP_RELAYS = 10
internal const val MAX_CALLBACK_URL_LENGTH = 8_000
internal const val MAX_ZAP_COMMENT_LENGTH = 280

/** Zap を送る投稿。リポストのカードでも元の投稿と作者にする。 */
data class ZapTarget(
    val eventId: String,
    val authorPubkey: String,
    val kind: Int,
    /** 投稿の `zap` タグ（分配）があるか。第1段階では送らない。 */
    val hasZapSplit: Boolean = false,
    /** チャンネルの推奨リレーなど、レシートを受け取りたい追加のリレー。 */
    val extraRelays: List<String> = emptyList(),
) {
    companion object {
        fun of(event: NostrEvent, extraRelays: List<String> = emptyList()) = ZapTarget(
            eventId = event.id,
            authorPubkey = event.pubkey,
            kind = event.kind,
            hasZapSplit = event.tags.any { it.firstOrNull() == "zap" },
            extraRelays = extraRelays,
        )
    }
}

/**
 * レシートを送ってもらうリレー。自分の読み込みリレー（件数を取る場所）を先に、作者の read リレーを後に並べる。
 * `wss://` だけを、重複を除いて最大 [MAX_ZAP_RELAYS] 個。
 */
fun zapRelays(ownReadRelays: List<String>, extraRelays: List<String>, authorReadRelays: List<String>): List<String> =
    (ownReadRelays + extraRelays + authorReadRelays)
        .map { it.trim().trimEnd('/') }
        .filter { it.startsWith("wss://") && it.length > 6 }
        .distinct()
        .take(MAX_ZAP_RELAYS)

/** kind 9734 のタグ（NIP-57 Appendix A）。 */
fun zapRequestTags(target: ZapTarget, amountMsats: Long, lnurl: String, relays: List<String>): List<List<String>> =
    buildList {
        add(listOf("relays") + relays)
        add(listOf("amount", amountMsats.toString()))
        add(listOf("lnurl", lnurl))
        add(listOf("p", target.authorPubkey))
        add(listOf("e", target.eventId))
        add(listOf("k", target.kind.toString()))
    }

/**
 * callback の URL。`nostr` には署名済みの Zap リクエストの JSON をそのまま渡し、パラメーターとして全体をエンコードする。
 * Zap に対応しない受け取り先では [zapRequestJson] を null にする。
 */
fun buildCallbackUrl(
    endpoint: PayEndpoint,
    amountMsats: Long,
    zapRequestJson: String?,
    comment: String,
): String {
    val builder = URLBuilder(endpoint.callback)
    builder.parameters.append("amount", amountMsats.toString())
    if (zapRequestJson != null) builder.parameters.append("nostr", zapRequestJson)
    builder.parameters.append("lnurl", endpoint.lnurl)
    if (comment.isNotEmpty() && endpoint.commentAllowed > 0) {
        builder.parameters.append("comment", comment.take(endpoint.commentAllowed))
    }
    val url = builder.buildString()
    if (url.length > MAX_CALLBACK_URL_LENGTH) throw ZapException("コメントを短くしてください")
    return url
}

/** ログに出すため、`nostr=` 以降を伏せる。 */
internal fun redactCallbackUrl(url: String): String = url.substringBefore("nostr=") + if ("nostr=" in url) "nostr=…" else ""
