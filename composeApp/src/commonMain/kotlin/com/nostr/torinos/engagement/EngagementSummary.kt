package com.nostr.torinos.engagement

import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.incrementedWithUnicodeReaction
import com.nostr.torinos.model.isLikeReaction
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toReactionOption
import com.nostr.torinos.model.toUnicodeReaction

/** 投稿カードに出す付随情報。[TargetEngagement] から毎回導出し、保存しない。 */
data class EngagementSummary(
    val reactionCount: Int = 0,
    val likeCount: Int = 0,
    val customReactions: List<CustomReaction> = emptyList(),
    val unicodeReactions: List<UnicodeReaction> = emptyList(),
    val ownLikeEventId: String? = null,
    /** 自分の絵文字リアクション。`ReactionOption.key` → イベントID。 */
    val ownEmojiReactionEventIds: Map<String, String> = emptyMap(),
    /** リポスト（kind 6 / 16）と引用の合計。 */
    val repostCount: Int = 0,
    val ownRepostEventId: String? = null,
    val replyCount: Int = 0,
)

/**
 * [relayScope] を渡すと、そのリレーから受信したイベントだけを数える（特定リレーのフィード用）。
 * 同じ人の複数のリアクションはそれぞれ数える（現行のフィードと同じ）。
 */
fun summarizeEngagement(
    engagement: TargetEngagement,
    ownPubkey: String?,
    relayScope: Set<String>? = null,
): EngagementSummary {
    val scoped = engagement.scopedTo(relayScope)
    fun Map<String, NostrEvent>.ordered() =
        values.sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })

    val reactions = scoped.reactions.ordered()
    val reposts = scoped.reposts.ordered()
    val own = reactions.filter { ownPubkey != null && it.pubkey == ownPubkey }
    return EngagementSummary(
        reactionCount = reactions.size,
        likeCount = reactions.count { it.isLikeReaction() },
        customReactions = reactions.mapNotNull { it.toCustomReaction() }
            .fold(emptyList()) { list, reaction -> list.incrementedWith(reaction) },
        unicodeReactions = reactions.mapNotNull { it.toUnicodeReaction() }
            .fold(emptyList()) { list, reaction -> list.incrementedWithUnicodeReaction(reaction) },
        ownLikeEventId = own.lastOrNull { it.isLikeReaction() }?.id,
        ownEmojiReactionEventIds = own.mapNotNull { event -> event.toReactionOption()?.let { it.key to event.id } }.toMap(),
        repostCount = reposts.size + scoped.quotes.size,
        ownRepostEventId = reposts.lastOrNull { ownPubkey != null && it.pubkey == ownPubkey }?.id,
        replyCount = scoped.replies.size,
    )
}

/** [relayScope] のリレーから受信したイベントだけに絞る。null なら絞らない。 */
fun TargetEngagement.scopedTo(relayScope: Set<String>?): TargetEngagement {
    if (relayScope == null) return this
    fun Map<String, NostrEvent>.scoped() = filterKeys { id -> sourceRelays[id].orEmpty().any { it in relayScope } }
    return copy(reactions = reactions.scoped(), reposts = reposts.scoped(), replies = replies.scoped(), quotes = quotes.scoped())
}

/** 表示の楽観更新（[EngagementReducer]）が扱う形にする。 */
fun EngagementSummary.toNoteEngagementState(): NoteEngagementState = NoteEngagementState(
    reactionCount = reactionCount,
    likeReactionCount = likeCount,
    customReactions = customReactions,
    unicodeReactions = unicodeReactions,
    ownLikeEventId = ownLikeEventId,
    ownEmojiReactionEventIds = ownEmojiReactionEventIds,
    repostCount = repostCount,
    ownRepostEventId = ownRepostEventId,
)
