package com.nostr.torinos.ui.search

import com.nostr.torinos.model.isLikeReaction
import com.nostr.torinos.engagement.ReactionEventReducer
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.incrementedWithUnicodeReaction
import com.nostr.torinos.model.isSupportedTimelineComment
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toUnicodeReaction
import com.nostr.torinos.network.reactionTargetId

/** 検索結果の1投稿に表示する返信・リアクション・リポストの集計。 */
internal data class SearchEngagement(
    val replyCount: Int = 0,
    val reactionCount: Int = 0,
    val likeReactionCount: Int = 0,
    val customReactions: List<CustomReaction> = emptyList(),
    val unicodeReactions: List<UnicodeReaction> = emptyList(),
    val reactionEvents: List<NostrEvent> = emptyList(),
    val repostCount: Int = 0,
    val repostPubkeys: List<String> = emptyList(),
)

/**
 * 検索結果の付随イベントを対象投稿ごとに集計する。
 *
 * 重複排除の索引と集計値を対象ごとに同じ寿命で持つため、索引だけが先に消えて同じイベントを
 * 再計上することはない。追跡上限を超えて外れた対象は集計値だけを残し、以後のイベントを受け付けない。
 */
internal class SearchEngagementAccumulator(private val maxTrackedTargets: Int) {
    private class TargetIndex {
        val reactionIds = mutableSetOf<String>()
        val replyIds = mutableSetOf<String>()
        /** kind 6 のIDと、引用（kind 1 の q タグ）のID。 */
        val repostIds = mutableSetOf<String>()
    }

    private val tracked = LinkedHashMap<String, TargetIndex>()
    private val engagements = mutableMapOf<String, SearchEngagement>()
    private var snapshot: Map<String, SearchEngagement> = emptyMap()
    private var dirty = false

    val trackedTargets: Set<String> get() = tracked.keys

    fun isTracked(eventId: String): Boolean = eventId in tracked

    /** 新しい対象なら追跡を始めて true を返す。上限を超えた分は古い対象から外す。 */
    fun track(eventId: String): Boolean {
        if (eventId in tracked) return false
        tracked[eventId] = TargetIndex()
        while (tracked.size > maxTrackedTargets) tracked.remove(tracked.keys.first())
        return true
    }

    /** 集計に加えたイベントの作者のうち、プロフィールを表示に使うもの。 */
    fun apply(event: NostrEvent): Set<String> = when (event.kind) {
        REACTION_KIND -> applyReaction(event)
        REPOST_KIND -> applyRepost(event)
        COMMENT_EVENT_KIND -> {
            if (event.isSupportedTimelineComment()) applyReply(event)
            emptySet()
        }
        TEXT_NOTE_KIND -> {
            applyReply(event)
            applyQuote(event)
        }
        else -> emptySet()
    }

    /** 前回の取り出し以降に変化があった場合だけ新しいスナップショットを作る。 */
    fun snapshot(): Map<String, SearchEngagement> {
        if (dirty) {
            snapshot = engagements.toMap()
            dirty = false
        }
        return snapshot
    }

    fun clear() {
        tracked.clear()
        engagements.clear()
        snapshot = emptyMap()
        dirty = false
    }

    private fun applyReaction(event: NostrEvent): Set<String> {
        val targetId = event.reactionTargetId() ?: return emptySet()
        val index = tracked[targetId] ?: return emptySet()
        if (!index.reactionIds.add(event.id)) return emptySet()
        update(targetId) { current ->
            current.copy(
                reactionCount = current.reactionCount + 1,
                likeReactionCount = current.likeReactionCount + if (event.isLikeReaction()) 1 else 0,
                customReactions = event.toCustomReaction()
                    ?.let(current.customReactions::incrementedWith) ?: current.customReactions,
                unicodeReactions = event.toUnicodeReaction()
                    ?.let(current.unicodeReactions::incrementedWithUnicodeReaction) ?: current.unicodeReactions,
                reactionEvents = ReactionEventReducer.add(current.reactionEvents, event),
            )
        }
        return setOf(event.pubkey)
    }

    private fun applyReply(event: NostrEvent) {
        val targetId = event.replyTargetId() ?: return
        val index = tracked[targetId] ?: return
        if (!index.replyIds.add(event.id)) return
        update(targetId) { it.copy(replyCount = it.replyCount + 1) }
    }

    private fun applyRepost(event: NostrEvent): Set<String> {
        val targetId = event.reactionTargetId() ?: return emptySet()
        return if (addRepost(targetId, event.id, event.pubkey)) setOf(event.pubkey) else emptySet()
    }

    private fun applyQuote(event: NostrEvent): Set<String> {
        val added = event.tags
            .filter { it.firstOrNull() == "q" }
            .mapNotNull { it.getOrNull(1) }
            .distinct()
            .count { targetId -> addRepost(targetId, event.id, event.pubkey) }
        return if (added > 0) setOf(event.pubkey) else emptySet()
    }

    private fun addRepost(targetId: String, eventId: String, pubkey: String): Boolean {
        val index = tracked[targetId] ?: return false
        if (!index.repostIds.add(eventId)) return false
        update(targetId) { current ->
            current.copy(
                repostCount = current.repostCount + 1,
                repostPubkeys = if (pubkey in current.repostPubkeys) current.repostPubkeys else current.repostPubkeys + pubkey,
            )
        }
        return true
    }

    private inline fun update(targetId: String, transform: (SearchEngagement) -> SearchEngagement) {
        engagements[targetId] = transform(engagements[targetId] ?: SearchEngagement())
        dirty = true
    }

    private companion object {
        const val TEXT_NOTE_KIND = 1
        const val REPOST_KIND = 6
        const val REACTION_KIND = 7
    }
}
