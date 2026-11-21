package com.nostr.torinos.chat

import com.nostr.torinos.network.normalizeRelayUrl

/** kind 10009（NIP-51 Simple groups）の1件。 */
data class JoinedGroup(val ref: GroupRef, val name: String?)

/** `["group", <id>, <relay>, <name?>]` を読む。不正なものと重複（先勝ち）は捨てる。 */
fun parseJoinedGroups(tags: List<List<String>>): List<JoinedGroup> {
    val seen = mutableSetOf<GroupRef>()
    return tags.mapNotNull { tag ->
        if (tag.firstOrNull() != "group") return@mapNotNull null
        val groupId = tag.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val relayUrl = tag.getOrNull(2)?.let(::normalizeRelayUrl) ?: return@mapNotNull null
        val ref = GroupRef(relayUrl, groupId)
        if (!seen.add(ref)) return@mapNotNull null
        JoinedGroup(ref, tag.getOrNull(3)?.takeIf { it.isNotBlank() })
    }
}

private fun List<String>.groupRefOrNull(): GroupRef? {
    if (firstOrNull() != "group") return null
    val groupId = getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
    val relayUrl = getOrNull(2)?.let(::normalizeRelayUrl) ?: return null
    return GroupRef(relayUrl, groupId)
}

/** 未知のタグを保ったまま [group] を足す。既にあれば何も変えない。リレーの `r` タグも無ければ足す。 */
fun withJoinedGroup(tags: List<List<String>>, group: JoinedGroup): List<List<String>> {
    if (tags.any { it.groupRefOrNull() == group.ref }) return tags
    val relay = group.ref.relayUrl
    return buildList {
        addAll(tags)
        add(listOfNotNull("group", group.ref.groupId, relay, group.name?.takeIf { it.isNotBlank() }))
        if (tags.none { it.firstOrNull() == "r" && it.getOrNull(1)?.let(::normalizeRelayUrl) == relay }) {
            add(listOf("r", relay))
        }
    }
}

/**
 * [ref] を外す。そのリレーのグループが無くなれば `r` タグも外す。
 * ただし [keepRelays]（参加中の Buzz コミュニティ）の `r` は残す。
 */
fun withoutJoinedGroup(tags: List<List<String>>, ref: GroupRef, keepRelays: Set<String> = emptySet()): List<List<String>> {
    val remaining = tags.filterNot { it.groupRefOrNull() == ref }
    val stillUsed = ref.relayUrl in keepRelays || remaining.any { it.groupRefOrNull()?.relayUrl == ref.relayUrl }
    if (stillUsed) return remaining
    return remaining.filterNot { it.firstOrNull() == "r" && it.getOrNull(1)?.let(::normalizeRelayUrl) == ref.relayUrl }
}
