package com.nostr.torinos.engagement

import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.isSupportedTimelineComment
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 1つの投稿に付随するイベント。すべてイベントIDをキーにした集合で、同じイベントは1件になる。 */
data class TargetEngagement(
    val reactions: Map<String, NostrEvent> = emptyMap(),
    /** kind 6 / 16。 */
    val reposts: Map<String, NostrEvent> = emptyMap(),
    /** 直接の返信（kind 1、対応する kind 1111）。 */
    val replies: Map<String, NostrEvent> = emptyMap(),
    /** q タグでこの投稿を引用した投稿。 */
    val quotes: Map<String, NostrEvent> = emptyMap(),
    /** イベントIDごとの受信元リレー。特定リレーのフィードが、そのリレーの分だけを数えるのに使う。 */
    val sourceRelays: Map<String, Set<String>> = emptyMap(),
)

/**
 * 投稿に付随するリアクション・リポスト・返信・引用を、投稿ごとのイベント集合として持つ。
 *
 * 件数は持たず、集合から [summarizeEngagement] で導出する。何度同じイベントを入れても結果は変わらない。
 * 保持するのは、どこかの画面が [retain] している投稿の分だけ。どの画面も保持しなくなったら、
 * その投稿の集合をまとめて捨てる。まだ保持されていない投稿へのイベントは、投稿より先に届くことがあるため
 * （タイムラインのリポストなど）、[maxUnassignedEvents] 件まで取っておき、保持されたときに加える。
 */
class EngagementStore(
    private val maxUnassignedEvents: Int = 5_000,
) {
    private class Entry {
        val reactions = linkedMapOf<String, NostrEvent>()
        val reposts = linkedMapOf<String, NostrEvent>()
        val replies = linkedMapOf<String, NostrEvent>()
        val quotes = linkedMapOf<String, NostrEvent>()
        val sources = mutableMapOf<String, MutableSet<String>>()
        val localCommits = mutableMapOf<String, Pair<String, Long>>()

        fun buckets() = listOf(reactions, reposts, replies, quotes)
    }

    private val lock = SynchronousLock()
    private val owners = mutableMapOf<Any, Set<String>>()
    private val retainCounts = mutableMapOf<String, Int>()
    private val entries = mutableMapOf<String, Entry>()
    /** 削除されたイベントID → 削除した作者。削除がイベントより先に届いた場合にも使う。 */
    private val deletedBy = linkedMapOf<String, String>()
    /** 保持されていない投稿へのイベント（イベントID → イベントと受信元）。古い順に捨てる。 */
    private val unassigned = linkedMapOf<String, Pair<NostrEvent, MutableSet<String>>>()
    private class Subscriber {
        val pending = mutableSetOf<String>()
        val signal = Channel<Unit>(Channel.CONFLATED)
    }
    private val subscribers = mutableListOf<Subscriber>()
    private var revision = 0L
    private val localSequences = mutableMapOf<String, Long>()

    internal fun revision(): Long = lock.withLock { revision }
    internal fun localCommitSequence(sessionId: String?): Long = lock.withLock { localSequences[sessionId] ?: 0L }
    internal fun localEventsSince(targetId: String, sessionId: String?, since: Long): Set<String> = lock.withLock {
        entries[targetId]?.localCommits.orEmpty().filterValues { it.first == sessionId && it.second > since }.keys.toSet()
    }

    internal fun observeLocal(event: NostrEvent, sessionId: String?) {
        if (sessionId == null) { observe(event, null); return }
        val changed = lock.withLock {
            val changed = (if (event.kind == DELETION_KIND) applyDeletionLocked(event) else addLocked(event, null)).toMutableSet()
            val sequence = (localSequences[sessionId] ?: 0L) + 1
            localSequences[sessionId] = sequence
            targetsOf(event).forEach { (targetId, bucket) ->
                entries[targetId]?.let { entry -> if (event.id in bucket(entry)) {
                    entry.localCommits[event.id] = sessionId to sequence
                    changed += targetId
                } }
            }
            revision++
            changed
        }
        if (changed.isNotEmpty()) notifyChanged(changed)
    }

    internal fun releaseLocalSession(sessionId: String) = lock.withLock {
        localSequences.remove(sessionId)
        entries.values.forEach { entry -> entry.localCommits.entries.removeAll { it.value.first == sessionId } }
        revision++
    }

    /**
     * 中身が変わった投稿ID。受け手ごとに変化を溜めて渡すので、受け手が遅くても取りこぼさず、
     * 続けて変わった分はまとめて届く。
     */
    val changes: Flow<Set<String>> = flow {
        val subscriber = Subscriber()
        lock.withLock { subscribers += subscriber }
        try {
            for (unit in subscriber.signal) {
                val batch = lock.withLock { subscriber.pending.toSet().also { subscriber.pending.clear() } }
                if (batch.isNotEmpty()) emit(batch)
            }
        } finally {
            lock.withLock { subscribers -= subscriber }
        }
    }

    fun observe(event: NostrEvent, sourceRelay: String?) {
        val changed = lock.withLock {
            val changed = if (event.kind == DELETION_KIND) applyDeletionLocked(event) else addLocked(event, sourceRelay)
            if (changed.isNotEmpty()) revision++
            changed
        }
        if (changed.isNotEmpty()) notifyChanged(changed)
    }

    /** [owner] が保持する投稿を [targetIds] に置き換える。 */
    fun retain(owner: Any, targetIds: Set<String>) {
        lock.withLock {
            val previous = owners[owner].orEmpty()
            if (previous != targetIds) revision++
            if (targetIds.isEmpty()) owners.remove(owner) else owners[owner] = targetIds
            val added = (targetIds - previous).filter { targetId ->
                retainCounts[targetId] = (retainCounts[targetId] ?: 0) + 1
                targetId !in entries
            }.toSet()
            added.forEach { entries[it] = Entry() }
            if (added.isNotEmpty()) attachUnassignedLocked(added)
            (previous - targetIds).forEach { targetId ->
                val count = (retainCounts[targetId] ?: 0) - 1
                if (count > 0) {
                    retainCounts[targetId] = count
                } else {
                    retainCounts.remove(targetId)
                    entries.remove(targetId)
                }
            }
        }
    }

    fun release(owner: Any) = retain(owner, emptySet())

    private fun notifyChanged(targetIds: Set<String>) {
        val targets = lock.withLock {
            subscribers.onEach { it.pending += targetIds }.toList()
        }
        targets.forEach { it.signal.trySend(Unit) }
    }

    fun snapshot(targetId: String): TargetEngagement? = lock.withLock {
        val entry = entries[targetId] ?: return@withLock null
        TargetEngagement(
            reactions = entry.reactions.toMap(),
            reposts = entry.reposts.toMap(),
            replies = entry.replies.toMap(),
            quotes = entry.quotes.toMap(),
            sourceRelays = entry.sources.mapValues { (_, relays) -> relays.toSet() },
        )
    }

    internal fun clearForTest(): Unit = lock.withLock {
        owners.clear()
        retainCounts.clear()
        entries.clear()
        deletedBy.clear()
        unassigned.clear()
        localSequences.clear()
        revision++
        Unit
    }

    private fun addLocked(event: NostrEvent, sourceRelay: String?): Set<String> {
        if (deletedBy[event.id] == event.pubkey) return emptySet()
        val targets = targetsOf(event)
        if (targets.any { (targetId, _) -> targetId !in entries }) rememberUnassignedLocked(event, sourceRelay)
        val changed = mutableSetOf<String>()
        targets.forEach { (targetId, bucket) ->
            val entry = entries[targetId] ?: return@forEach
            val events = bucket(entry)
            if (event.id !in events) {
                events[event.id] = event
                changed += targetId
            }
            if (sourceRelay != null && entry.sources.getOrPut(event.id) { mutableSetOf() }.add(sourceRelay)) {
                changed += targetId
            }
        }
        return changed
    }

    private fun rememberUnassignedLocked(event: NostrEvent, sourceRelay: String?) {
        val relays = unassigned.remove(event.id)?.second ?: mutableSetOf()
        sourceRelay?.let(relays::add)
        unassigned[event.id] = event to relays
        while (unassigned.size > maxUnassignedEvents) unassigned.remove(unassigned.keys.first())
    }

    private fun attachUnassignedLocked(targetIds: Set<String>) {
        unassigned.values.forEach { (event, relays) ->
            targetsOf(event).forEach { (targetId, bucket) ->
                if (targetId !in targetIds) return@forEach
                val entry = entries.getValue(targetId)
                bucket(entry)[event.id] = event
                if (relays.isNotEmpty()) entry.sources.getOrPut(event.id) { mutableSetOf() }.addAll(relays)
            }
        }
    }

    private fun applyDeletionLocked(deletion: NostrEvent): Set<String> {
        val changed = mutableSetOf<String>()
        deletion.tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) }.distinct().forEach { eventId ->
            if (unassigned[eventId]?.first?.pubkey == deletion.pubkey) unassigned.remove(eventId)
            deletedBy.remove(eventId)
            deletedBy[eventId] = deletion.pubkey
            while (deletedBy.size > MAX_DELETED_IDS) deletedBy.remove(deletedBy.keys.first())
            entries.forEach { (targetId, entry) ->
                entry.buckets().forEach { events ->
                    val target = events[eventId]
                    // 削除できるのは作者だけ（NIP-09）。
                    if (target != null && target.pubkey == deletion.pubkey) {
                        events.remove(eventId)
                        entry.sources.remove(eventId)
                        entry.localCommits.remove(eventId)
                        changed += targetId
                    }
                }
            }
        }
        return changed
    }

    private fun targetsOf(event: NostrEvent): List<Pair<String, (Entry) -> MutableMap<String, NostrEvent>>> =
        classifyEngagementEvent(event).map { reference ->
            reference.targetId to { entry: Entry -> when (reference.bucket) {
                EngagementBucket.Reactions -> entry.reactions
                EngagementBucket.Reposts -> entry.reposts
                EngagementBucket.Replies -> entry.replies
                EngagementBucket.Quotes -> entry.quotes
            } }
        }

    companion object {
        /** アカウントによらず共有する。イベント自体は公開データで、自分の状態は導出時に決める。 */
        val shared = EngagementStore()

        private const val REACTION_KIND = 7
        private const val REPOST_KIND = 6
        private const val GENERIC_REPOST_KIND = 16
        private const val DELETION_KIND = 5
        private const val MAX_DELETED_IDS = 20_000
    }
}
