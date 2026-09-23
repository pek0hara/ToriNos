package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.cacheTraceLog
import com.nostr.torinos.util.withLock

/**
 * 画面をまたいで再利用する、セッション内のリアクションイベントキャッシュ。
 *
 * キャッシュに存在しないことは取得完了やリアクション0件を意味しない。各画面はこれを
 * 初期表示に利用しつつ、必要なリレー購読を継続する。
 */
object ReactionEventStore {
    private val lock = SynchronousLock()
    private val reactionsById = LinkedHashMap<String, CachedReaction>()
    private val targetAuthorsByEventId = LinkedHashMap<String, String>()
    private val referencedTargetCounts = mutableMapOf<String, Int>()

    fun observe(event: NostrEvent, sourceRelayUrls: Set<String> = emptySet()) {
        lock.withLock {
            when (event.kind) {
                REACTION_KIND -> observeReactionLocked(event, sourceRelayUrls)
                DELETION_KIND -> observeDeletionLocked(event, sourceRelayUrls)
                else -> rememberTargetAuthorLocked(event.id, event.pubkey)
            }
        }
    }

    internal fun matching(
        filters: List<NostrFilter>,
        allowedRelayUrls: Set<String>? = null,
    ): List<NostrEvent> = lock.withLock {
        filters
            .asSequence()
            .filter { it.kinds?.contains(REACTION_KIND) == true }
            .flatMap { filter ->
                reactionsById.values
                    .asSequence()
                    .filter { cached ->
                        allowedRelayUrls == null || cached.sourceRelayUrls.any { it in allowedRelayUrls }
                    }
                    .map { it.event }
                    .filter { event -> event.matchesReactionFilter(filter, targetAuthorsByEventId) }
                    .sortedByDescending { it.createdAt }
                    .let { events -> filter.limit?.let(events::take) ?: events }
            }
            .distinctBy { it.id }
            .toList()
    }

    internal fun isAddressedTo(event: NostrEvent, pubkey: String): Boolean = lock.withLock {
        isAddressedToLocked(event, pubkey)
    }

    private fun isAddressedToLocked(event: NostrEvent, pubkey: String): Boolean {
        val knownTargetAuthor = event.reactionTargetId()?.let(targetAuthorsByEventId::get)
        if (knownTargetAuthor != null) return knownTargetAuthor == pubkey
        return event.tags.any { tag -> tag.firstOrNull() == "p" && tag.getOrNull(1) == pubkey }
    }

    internal fun receivedReactions(
        pubkey: String,
        since: Long,
        until: Long,
    ): List<NostrEvent> = lock.withLock {
        reactionsById.values
            .asSequence()
            .map { it.event }
            .filter { event ->
                event.createdAt in since..until &&
                    event.content.trim() != "-" &&
                    isAddressedToLocked(event, pubkey)
            }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    internal fun clearForTest() {
        lock.withLock {
            reactionsById.clear()
            targetAuthorsByEventId.clear()
            referencedTargetCounts.clear()
        }
    }

    internal fun cacheSizesForTest(): Pair<Int, Int> = lock.withLock {
        reactionsById.size to targetAuthorsByEventId.size
    }

    private fun observeReactionLocked(event: NostrEvent, sourceRelayUrls: Set<String>) {
        val targetId = event.reactionTargetId() ?: return
        val existing = reactionsById[event.id]
        val updated = CachedReaction(
            event = event,
            sourceRelayUrls = existing?.sourceRelayUrls.orEmpty() + sourceRelayUrls,
        )
        if (existing?.event?.reactionTargetId() != targetId) {
            existing?.event?.reactionTargetId()?.let(::decrementTargetReferenceLocked)
            referencedTargetCounts[targetId] = (referencedTargetCounts[targetId] ?: 0) + 1
        }
        reactionsById[event.id] = updated
        trimReactionsLocked()
    }

    private fun observeDeletionLocked(event: NostrEvent, sourceRelayUrls: Set<String>) {
        event.tags
            .asSequence()
            .filter { it.firstOrNull() == "e" }
            .mapNotNull { it.getOrNull(1) }
            .distinct()
            .forEach { id ->
                val cached = reactionsById[id] ?: return@forEach
                if (cached.event.pubkey != event.pubkey) return@forEach
                if (sourceRelayUrls.isEmpty()) {
                    removeReactionLocked(id)
                } else {
                    val remainingSources = cached.sourceRelayUrls - sourceRelayUrls
                    if (remainingSources.isEmpty()) {
                        removeReactionLocked(id)
                    } else {
                        reactionsById[id] = cached.copy(sourceRelayUrls = remainingSources)
                    }
                }
            }
    }

    private fun rememberTargetAuthorLocked(eventId: String, pubkey: String) {
        if (targetAuthorsByEventId[eventId] == pubkey) return
        targetAuthorsByEventId.remove(eventId)
        targetAuthorsByEventId[eventId] = pubkey
        while (targetAuthorsByEventId.size > MAX_CACHED_TARGET_AUTHORS) {
            val removableId = targetAuthorsByEventId.keys
                .firstOrNull { (referencedTargetCounts[it] ?: 0) == 0 }
                ?: targetAuthorsByEventId.keys.first()
            targetAuthorsByEventId.remove(removableId)
            cacheTraceLog {
                "[ReactionEventStore] evicted targetAuthor eventId=$removableId size=${targetAuthorsByEventId.size}"
            }
        }
    }

    private fun trimReactionsLocked() {
        while (reactionsById.size > MAX_CACHED_REACTIONS) {
            val oldestId = reactionsById.minByOrNull { it.value.event.createdAt }?.key ?: return
            removeReactionLocked(oldestId)
            cacheTraceLog { "[ReactionEventStore] evicted reaction id=$oldestId size=${reactionsById.size}" }
        }
    }

    private fun removeReactionLocked(eventId: String) {
        val removed = reactionsById.remove(eventId) ?: return
        removed.event.reactionTargetId()?.let(::decrementTargetReferenceLocked)
    }

    private fun decrementTargetReferenceLocked(targetId: String) {
        val count = referencedTargetCounts[targetId] ?: return
        if (count <= 1) referencedTargetCounts.remove(targetId)
        else referencedTargetCounts[targetId] = count - 1
    }
}

/** Empty sourceRelayUrls means the source is unknown (source-agnostic callers/tests only). */
private data class CachedReaction(
    val event: NostrEvent,
    val sourceRelayUrls: Set<String>,
)

internal fun NostrEvent.reactionTargetId(): String? =
    tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)

private fun NostrEvent.matchesReactionFilter(
    filter: NostrFilter,
    targetAuthors: Map<String, String>,
): Boolean {
    if (kind !in filter.kinds.orEmpty()) return false
    if (filter.ids != null && id !in filter.ids) return false
    if (filter.authors != null && pubkey !in filter.authors) return false
    if (filter.since != null && createdAt < filter.since) return false
    if (filter.until != null && createdAt > filter.until) return false

    val targetId = reactionTargetId()
    if (filter.eTags != null && targetId !in filter.eTags) return false
    if (filter.pTags != null) {
        val knownTargetAuthor = targetId?.let(targetAuthors::get)
        val taggedPubkeys = tags
            .asSequence()
            .filter { it.firstOrNull() == "p" }
            .mapNotNull { it.getOrNull(1) }
            .toSet()
        val matchesTarget = if (knownTargetAuthor != null) {
            knownTargetAuthor in filter.pTags
        } else {
            taggedPubkeys.any { it in filter.pTags }
        }
        if (!matchesTarget) return false
    }
    return true
}

private const val REACTION_KIND = 7
private const val DELETION_KIND = 5
private const val MAX_CACHED_REACTIONS = 10_000
private const val MAX_CACHED_TARGET_AUTHORS = 10_000
