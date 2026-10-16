package com.nostr.torinos.ui.search

import com.nostr.torinos.engagement.*
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

/** 取得方式は検索固有、集計規則とイベントは共通ストアを使う。 */
internal class SearchEngagementView(
    private val maxTrackedTargets: Int,
    internal val store: EngagementStore = EngagementStore(),
    private val sessionId: String? = null,
    private val ownPubkey: String? = null,
) {
    private val tracked = linkedSetOf<String>()
    private val admitted = mutableMapOf<String, MutableSet<String>>()
    private val frozen = mutableMapOf<String, SearchEngagement>()
    private var startSequence = store.localCommitSequence(sessionId)
    private var cached = emptyMap<String, SearchEngagement>()
    private var dirty = false
    private var storeRevision = -1L
    internal val pending = com.nostr.torinos.engagement.PendingEngagementRegistry.forSession(sessionId)
    private var pendingRevision = -1L

    val trackedTargets: Set<String> get() = tracked.toSet()
    fun isTracked(id: String) = id in tracked
    fun track(eventId: String): Boolean {
        if (!tracked.add(eventId)) return false
        admitted[eventId] = linkedSetOf()
        while (tracked.size > maxTrackedTargets) {
            val old = tracked.first()
            frozen[old] = project(old, includePending = false)
            tracked.remove(old)
            admitted.remove(old)
        }
        store.retain(this, tracked.toSet())
        dirty = true
        return true
    }

    fun apply(event: NostrEvent, relayUrl: String? = null): Set<String> {
        val references = classifyEngagementEvent(event).filter { it.targetId in tracked }
        store.observe(event, relayUrl)
        references.forEach { if (admitted.getValue(it.targetId).add(event.id)) dirty = true }
        return if (references.any { it.bucket != EngagementBucket.Replies }) setOf(event.pubkey) else emptySet()
    }

    private fun project(id: String, includePending: Boolean = true): SearchEngagement {
        val engagement = store.snapshot(id) ?: TargetEngagement()
        val ids = admitted[id].orEmpty() + store.localEventsSince(id, sessionId, startSequence)
        val projected = projectEngagement(engagement, ownPubkey, EngagementViewScope(admittedEventIds = ids), if (includePending) pending.operations(id) else emptyMap())
        val state = projected.state
        return SearchEngagement(
            replyCount = projected.replyCount,
            reactionCount = state.reactionCount, likeReactionCount = state.likeReactionCount,
            customReactions = state.customReactions, unicodeReactions = state.unicodeReactions,
            reactionEvents = projected.events.reactions.values.sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id }),
            repostCount = state.repostCount,
            repostPubkeys = (projected.events.reposts.values + projected.events.quotes.values).map { it.pubkey }.distinct(),
        )
    }

    fun snapshot(): Map<String, SearchEngagement> {
        val revision = store.revision()
        val operationsRevision = pending.revision()
        if (dirty || revision != storeRevision || operationsRevision != pendingRevision) {
            val next = frozen + tracked.associateWith { project(it) }
            if (revision == store.revision() && operationsRevision == pending.revision()) {
                cached = next
                dirty = false
                storeRevision = revision
                pendingRevision = operationsRevision
            }
        }
        return cached
    }

    fun clear() {
        tracked.clear(); admitted.clear(); frozen.clear()
        store.release(this)
        startSequence = store.localCommitSequence(sessionId)
        cached = emptyMap(); dirty = false; storeRevision = -1
    }
}

// 既存のテスト・呼び出し元の段階移行用。
internal typealias SearchEngagementAccumulator = SearchEngagementView
