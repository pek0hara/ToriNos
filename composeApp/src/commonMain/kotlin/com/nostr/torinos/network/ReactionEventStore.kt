package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 画面をまたいで再利用する、セッション内のリアクションイベントキャッシュ。
 *
 * キャッシュに存在しないことは取得完了やリアクション0件を意味しない。各画面はこれを
 * 初期表示に利用しつつ、必要なリレー購読を継続する。
 */
object ReactionEventStore {
    private val cache = MutableStateFlow<Map<String, CachedReaction>>(emptyMap())
    private val _events = MutableStateFlow<Map<String, NostrEvent>>(emptyMap())
    val events: StateFlow<Map<String, NostrEvent>> = _events.asStateFlow()

    private val _targetAuthors = MutableStateFlow<Map<String, String>>(emptyMap())

    fun observe(event: NostrEvent, sourceRelayUrls: Set<String> = emptySet()) {
        when (event.kind) {
            REACTION_KIND -> {
                if (event.reactionTargetId() == null) return
                cache.update { current ->
                    val existingSources = current[event.id]?.sourceRelayUrls.orEmpty()
                    (current + (event.id to CachedReaction(event, existingSources + sourceRelayUrls)))
                        .trimOldest()
                }
                publishEvents()
            }
            DELETION_KIND -> {
                val deletedIds = event.tags
                    .asSequence()
                    .filter { it.firstOrNull() == "e" }
                    .mapNotNull { it.getOrNull(1) }
                    .toSet()
                if (deletedIds.isEmpty()) return
                cache.update { current ->
                    buildMap {
                        current.forEach { (id, cached) ->
                            if (id !in deletedIds || cached.event.pubkey != event.pubkey) {
                                put(id, cached)
                            } else if (sourceRelayUrls.isNotEmpty()) {
                                val remainingSources = cached.sourceRelayUrls - sourceRelayUrls
                                if (remainingSources.isNotEmpty()) {
                                    put(id, cached.copy(sourceRelayUrls = remainingSources))
                                }
                            }
                        }
                    }
                }
                publishEvents()
            }
            else -> _targetAuthors.update { current ->
                if (current[event.id] == event.pubkey) current
                else (current + (event.id to event.pubkey)).trimOldestKeys(cache.value)
            }
        }
    }

    internal fun matching(
        filters: List<NostrFilter>,
        allowedRelayUrls: Set<String>? = null,
    ): List<NostrEvent> {
        val targetAuthors = _targetAuthors.value
        return filters
            .asSequence()
            .filter { it.kinds?.contains(REACTION_KIND) == true }
            .flatMap { filter ->
                cache.value.values
                    .asSequence()
                    .filter { cached ->
                        allowedRelayUrls == null || cached.sourceRelayUrls.any { it in allowedRelayUrls }
                    }
                    .map { it.event }
                    .filter { event -> event.matchesReactionFilter(filter, targetAuthors) }
                    .sortedByDescending { it.createdAt }
                    .let { events -> filter.limit?.let(events::take) ?: events }
            }
            .distinctBy { it.id }
            .toList()
    }

    internal fun isAddressedTo(event: NostrEvent, pubkey: String): Boolean {
        val knownTargetAuthor = event.reactionTargetId()?.let(_targetAuthors.value::get)
        if (knownTargetAuthor != null) return knownTargetAuthor == pubkey
        return event.tags.any { tag -> tag.firstOrNull() == "p" && tag.getOrNull(1) == pubkey }
    }

    internal fun receivedReactions(
        pubkey: String,
        since: Long,
        until: Long,
    ): List<NostrEvent> = cache.value.values
        .asSequence()
        .map { it.event }
        .filter { event ->
            event.createdAt in since..until &&
                event.content.trim() != "-" &&
                isAddressedTo(event, pubkey)
        }
        .sortedByDescending { it.createdAt }
        .toList()

    internal fun clearForTest() {
        cache.value = emptyMap()
        _events.value = emptyMap()
        _targetAuthors.value = emptyMap()
    }

    private fun publishEvents() {
        _events.value = cache.value.mapValues { it.value.event }
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

private fun Map<String, CachedReaction>.trimOldest(): Map<String, CachedReaction> {
    if (size <= MAX_CACHED_REACTIONS) return this
    val oldestId = minByOrNull { it.value.event.createdAt }?.key ?: return this
    return this - oldestId
}

private fun Map<String, String>.trimOldestKeys(
    reactions: Map<String, CachedReaction>,
): Map<String, String> {
    if (size <= MAX_CACHED_TARGET_AUTHORS) return this
    val referencedTargetIds = reactions.values.mapNotNullTo(hashSetOf()) { it.event.reactionTargetId() }
    val removableId = keys.firstOrNull { it !in referencedTargetIds } ?: keys.firstOrNull() ?: return this
    return this - removableId
}

private const val REACTION_KIND = 7
private const val DELETION_KIND = 5
private const val MAX_CACHED_REACTIONS = 10_000
private const val MAX_CACHED_TARGET_AUTHORS = 10_000
