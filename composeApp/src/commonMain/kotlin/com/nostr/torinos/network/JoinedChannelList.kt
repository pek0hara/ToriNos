package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent

/** NIP-51 kind 10005(Public chats)の純粋な操作(第7.3節・第16.17節)。 */
internal object JoinedChannelList {
    const val KIND = 10005

    /** replaceable event の最新(created_at 最大、同時刻は ID 最小。NIP-01)。 */
    fun latest(events: Collection<NostrEvent>, ownerPubkey: String): NostrEvent? =
        events.asSequence()
            .filter { it.kind == KIND && it.pubkey == ownerPubkey }
            .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
            .firstOrNull()

    /** 公開タグの `e` だけを参加中チャンネルとして読む。content(暗号化された非公開項目)は解釈しない。 */
    fun joinedIds(event: NostrEvent?): List<String> =
        event?.tags.orEmpty()
            .filter { it.firstOrNull() == "e" }
            .mapNotNull { it.getOrNull(1)?.takeIf(String::isNotBlank) }
            .distinct()

    /**
     * 最新のリストから次のリストを作る。既存のタグ(client を除く)と content はすべて保持し、対象の `e` タグだけを足す/除く。
     * 変更が無ければ null(送信しない)。created_at は前のリストより必ず後にする。
     */
    fun next(
        latest: NostrEvent?,
        channelId: String,
        join: Boolean,
        relayHint: String?,
        nowSeconds: Long,
    ): Update? {
        val tags = latest?.tags.orEmpty()
        val present = tags.any { it.firstOrNull() == "e" && it.getOrNull(1) == channelId }
        val nextTags = when {
            join && present -> return null
            join -> tags + listOf(buildList { add("e"); add(channelId); relayHint?.takeIf(String::isNotBlank)?.let(::add) })
            !present -> return null
            else -> tags.filterNot { it.firstOrNull() == "e" && it.getOrNull(1) == channelId }
        }
        val createdAt = maxOf(nowSeconds, (latest?.createdAt ?: 0) + 1)
        // client タグは発行したクライアントを示すので、前のリストのもの(例: Amethyst)は引き継がない(NIP-89)。
        val withClient = nextTags.filterNot { it.firstOrNull() == "client" } + listOf(listOf("client", "ToriNos"))
        return Update(withClient, latest?.content.orEmpty(), createdAt)
    }

    data class Update(val tags: List<List<String>>, val content: String, val createdAt: Long)
}
