package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent

/** 探せる公開グループ。 */
data class PublicGroupEntry(val ref: GroupRef, val metadata: GroupMetadata)

/** リレーから読んだ kind 39000 のうち、名前のある公開グループ（private・hidden でない）。 */
fun publicGroupEntries(relayUrl: String, events: Iterable<NostrEvent>, relayPubkey: String?): List<PublicGroupEntry> =
    latestGroupMetadata(events, relayPubkey).values
        .filter { !it.isPrivate && !it.isHidden && it.name.isNotBlank() && it.layout != GroupLayout.Hidden }
        .map { PublicGroupEntry(GroupRef(relayUrl, it.groupId), it) }

/**
 * 参加中のものを除いて、名前・説明・ID で絞り込む（大文字小文字を区別しない）。
 * 名前に当たったものを先に、それぞれ名前順に並べる。空の検索語ならすべて。
 */
fun searchPublicGroups(entries: List<PublicGroupEntry>, query: String, joined: Set<GroupRef>): List<PublicGroupEntry> {
    val q = query.trim().lowercase()
    val candidates = entries.filter { it.ref !in joined }.distinctBy { it.ref }
    val byName = compareBy<PublicGroupEntry> { it.metadata.name.lowercase() }.thenBy { it.ref.relayUrl }
    if (q.isEmpty()) return candidates.sortedWith(byName)
    val nameHits = candidates.filter { q in it.metadata.name.lowercase() }
    val otherHits = candidates.filter { it !in nameHits && (q in it.metadata.about.lowercase() || q in it.ref.groupId.lowercase()) }
    return nameHits.sortedWith(byName) + otherHits.sortedWith(byName)
}
