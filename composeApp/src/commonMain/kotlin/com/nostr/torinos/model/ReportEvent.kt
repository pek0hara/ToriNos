package com.nostr.torinos.model

/** NIP-56 の通報イベント。 */
const val REPORT_EVENT_KIND = 1984

/**
 * 投稿を通報する kind 1984 のタグ。NIP-56 では通報種別を通報対象の `e` タグの3要素目に入れる。
 * `p` タグは投稿者を示すだけで種別を付けない(付けるとユーザー自体の通報になる)。
 */
fun reportNoteTags(event: NostrEvent, reportType: String): List<List<String>> = listOf(
    listOf("e", event.id, reportType),
    listOf("p", event.pubkey),
)
