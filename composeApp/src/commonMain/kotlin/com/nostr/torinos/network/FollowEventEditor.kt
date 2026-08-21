package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent

internal data class FollowEventEdit(
    val content: String,
    val tags: List<List<String>>,
)

/** Damus と同様に、既存の kind:3 の対象 p タグだけを変更する。 */
internal fun editFollowEvent(
    event: NostrEvent,
    pubkey: String,
    shouldFollow: Boolean,
): FollowEventEdit? {
    require(event.kind == 3) { "kind:3 以外はフォロー更新に使用できません" }

    val matchingTag: (List<String>) -> Boolean = { tag ->
        tag.size >= 2 && tag[0] == "p" && tag[1] == pubkey
    }
    val updatedTags = if (shouldFollow) {
        if (event.tags.any(matchingTag)) return null
        event.tags + listOf(listOf("p", pubkey))
    } else {
        event.tags.filterNot(matchingTag)
    }

    if (updatedTags == event.tags) return null
    return FollowEventEdit(content = event.content, tags = updatedTags)
}

internal fun NostrEvent.followedPubkeys(): Set<String> = tags.followedPubkeys()

internal fun List<List<String>>.followedPubkeys(): Set<String> =
    asSequence()
        .filter { it.size >= 2 && it[0] == "p" }
        .map { it[1] }
        .toSet()

internal fun NostrEvent.isNewerThan(other: NostrEvent?): Boolean =
    other == null || createdAt > other.createdAt || (createdAt == other.createdAt && id < other.id)

internal fun NostrEvent.isNewerThan(other: NostrEvent?, fallbackCreatedAt: Long): Boolean =
    if (other != null) isNewerThan(other) else createdAt >= fallbackCreatedAt
