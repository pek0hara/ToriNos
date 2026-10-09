package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NoteContext

/** 件数取得で受信した返信を、チャンネルの表示制限を適用して親ごとにまとめる。 */
internal fun channelReplyPreviews(
    channelId: String,
    events: Collection<NostrEvent>,
    hiddenIds: Set<String> = emptySet(),
    mutedPubkeys: Set<String> = emptySet(),
    ngWords: Collection<String> = emptyList(),
): Map<String, List<NostrEvent>> {
    val context = NoteContext.Channel(channelId)
    return events.asSequence()
        .filter { context.matches(it) && context.replyTargetId(it) != null }
        .filter { it.id !in hiddenIds && it.pubkey !in mutedPubkeys }
        .filter { event -> ngWords.none { event.content.contains(it, ignoreCase = true) } }
        .distinctBy { it.id }
        .sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
        .groupBy { context.replyTargetId(it)!! }
}
