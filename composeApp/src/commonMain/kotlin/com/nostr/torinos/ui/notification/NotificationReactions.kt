package com.nostr.torinos.ui.notification

import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.incrementedWithUnicodeReaction
import com.nostr.torinos.model.isLikeReaction
import com.nostr.torinos.model.toCustomReaction

/** 投稿全体の累計ではなく、この通知グループに含まれるリアクションの内訳。 */
internal data class NotificationReactions(
    val totalCount: Int = 0,
    val likeCount: Int = 0,
    val customReactions: List<CustomReaction> = emptyList(),
    val unicodeReactions: List<UnicodeReaction> = emptyList(),
)

internal fun notificationReactions(events: List<NostrEvent>): NotificationReactions {
    var summary = NotificationReactions()
    events.filter { it.kind == 7 }.distinctBy { it.id }.forEach { event ->
        val custom = event.toCustomReaction()
        summary = when {
            event.isLikeReaction() -> summary.copy(likeCount = summary.likeCount + 1)
            custom != null -> summary.copy(customReactions = summary.customReactions.incrementedWith(custom))
            else -> {
                // NIP-25 の低評価や、絵文字以外の文字列も従来どおり表示する。
                val content = event.content.trim().let { if (it == "-") "👎" else it }
                summary.copy(unicodeReactions = summary.unicodeReactions.incrementedWithUnicodeReaction(UnicodeReaction(content)))
            }
        }.copy(totalCount = summary.totalCount + 1)
    }
    return summary
}
