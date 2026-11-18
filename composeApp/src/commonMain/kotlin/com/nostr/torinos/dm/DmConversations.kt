package com.nostr.torinos.dm

// 開封した DM（rumor）から会話一覧を組み立てる。docs/dm-feature-design.md §5.3-4・§8.2。

/** 開封した1件。rumor の ID・作者・宛先・時刻・本文。 */
data class DmMessage(
    val id: String,
    val sender: String,
    val recipients: List<String>,
    val createdAt: Long,
    val content: String,
    val tags: List<List<String>> = emptyList(),
)

fun Rumor.toDmMessage(): DmMessage = DmMessage(id, pubkey, recipients, createdAt, content, tags)

/** 会話の鍵。相手の公開鍵を並べたもの（自分とのメモは自分）。 */
fun dmConversationKey(peers: Set<String>): String = peers.sorted().joinToString(",")

fun DmMessage.peers(me: String): Set<String> = ((recipients + sender).toSet() - me).ifEmpty { setOf(me) }

data class DmConversationSummary(
    val key: String,
    /** 相手（並べ替え済み）。 */
    val peers: List<String>,
    val last: DmMessage,
    val messageCount: Int,
    val unread: Boolean,
    /** 相手が2人以上（段階1では開けない、§5.3-4）。 */
    val isGroup: Boolean,
)

data class DmConversationLists(
    /** フォローしている相手、または自分が一度でも送った会話。新しい順。 */
    val conversations: List<DmConversationSummary> = emptyList(),
    /** 知らない人からの、まだ返信していない会話。新しい順。 */
    val requests: List<DmConversationSummary> = emptyList(),
) {
    val unreadConversationCount: Int get() = conversations.count { it.unread }
}

/**
 * 会話一覧を作る。同じ rumor（自分宛ての写し・複数のリレー）は ID で1件にする。
 * ミュートした相手が1人でも入る会話は出さない。未読は、最後のメッセージが相手からで、
 * 既読の時刻（無ければ初めて受信した時刻 [readBaseline]）より新しいもの。
 */
fun buildDmConversations(
    messages: Collection<DmMessage>,
    me: String,
    follows: Set<String>,
    muted: Set<String>,
    lastRead: Map<String, Long>,
    readBaseline: Long,
): DmConversationLists {
    val byConversation = messages.distinctBy { it.id }.groupBy { it.peers(me) }
    val summaries = byConversation.mapNotNull { (peers, list) ->
        if (peers.any { it in muted }) return@mapNotNull null
        val key = dmConversationKey(peers)
        val last = list.maxWith(compareBy<DmMessage> { it.createdAt }.thenBy { it.id })
        val readUntil = lastRead[key] ?: readBaseline
        val summary = DmConversationSummary(
            key = key,
            peers = peers.sorted(),
            last = last,
            messageCount = list.size,
            unread = last.sender != me && last.createdAt > readUntil,
            isGroup = peers.size > 1,
        )
        val isRequest = peers.none { it in follows || it == me } && list.none { it.sender == me }
        summary to isRequest
    }.sortedWith(compareByDescending<Pair<DmConversationSummary, Boolean>> { it.first.last.createdAt }.thenBy { it.first.key })
    return DmConversationLists(
        conversations = summaries.filterNot { it.second }.map { it.first },
        requests = summaries.filter { it.second }.map { it.first },
    )
}
