package com.nostr.torinos.ui.notification

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.extractMentionedPubkeys
import com.nostr.torinos.model.extractNostrEventReferences
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.network.TargetLoadState
import com.nostr.torinos.network.isFullEventId

/**
 * kind 1 を通知に出すかどうか。
 *
 * `p` タグに自分が入っているだけでは出さない。スレッドで `p` タグが積み重なったものや、
 * 大量の `p` タグを付けたスパムが届くため。出すのは次のどれかに当たるときだけ。
 * - 本文で自分を言及している（npub / nprofile）
 * - 返信先が自分の投稿
 * - 自分の投稿や記事を引用・参照している
 */
sealed interface NotificationRelevance {
    data object Relevant : NotificationRelevance
    data object Unrelated : NotificationRelevance

    /** 作者の分からない返信先・引用先。取ってきた作者で決める。 */
    data class NeedsTargetAuthor(val eventIds: Set<String>) : NotificationRelevance
}

/** [ownEventIds] は作者が自分と分かっている投稿。返信先・引用先がこの中なら取ってこずに出す。 */
fun notificationRelevance(
    event: NostrEvent,
    ownPubkey: String,
    ownEventIds: Set<String> = emptySet(),
): NotificationRelevance {
    if (event.kind != 1) return NotificationRelevance.Relevant
    if (ownPubkey in extractMentionedPubkeys(event.content)) return NotificationRelevance.Relevant
    if (event.tags.any { it.firstOrNull() == "a" && it.getOrNull(1)?.split(':')?.getOrNull(1) == ownPubkey }) {
        return NotificationRelevance.Relevant
    }

    // (イベントID, 作者ヒント)。ヒントがあればそれを信じ、なければ取ってきて確かめる。
    val references = buildList {
        event.replyTargetId()?.let { replyId ->
            val tag = event.tags.firstOrNull { it.firstOrNull() == "e" && it.getOrNull(1) == replyId }
            add(replyId to tag?.getOrNull(4))
        }
        event.tags.filter { it.firstOrNull() == "q" }.forEach { add(it.getOrNull(1) to it.getOrNull(3)) }
        extractNostrEventReferences(event.content).forEach { add(it.eventId to it.authorPubkey) }
    }
    val unknown = linkedSetOf<String>()
    for ((id, hint) in references) {
        if (id == null || !isFullEventId(id)) continue
        if (id in ownEventIds) return NotificationRelevance.Relevant
        val author = hint?.lowercase()?.takeIf { isFullEventId(it) }
        when {
            author == null -> unknown += id
            author == ownPubkey -> return NotificationRelevance.Relevant
        }
    }
    return if (unknown.isEmpty()) NotificationRelevance.Unrelated else NotificationRelevance.NeedsTargetAuthor(unknown)
}

/**
 * 取ってきた返信先・引用先から決める。まだ取得中なら null。
 * 取れなかったものがあるときは、自分の投稿だった可能性を捨てずに出す。
 */
fun decideByTargetAuthors(
    eventIds: Set<String>,
    states: Map<String, TargetLoadState>,
    ownPubkey: String,
): Boolean? {
    val resolved = eventIds.map { states[it] }
    if (resolved.any { it is TargetLoadState.Resolved && it.event.pubkey == ownPubkey }) return true
    if (resolved.any { it == null || it == TargetLoadState.Idle || it == TargetLoadState.Loading }) return null
    return resolved.any { it !is TargetLoadState.Resolved }
}
