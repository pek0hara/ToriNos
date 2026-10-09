package com.nostr.torinos.journal

import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent

/** やりとりとして数えるイベントの種類。返信・コメント・リポスト・リアクション。 */
internal val JournalInteractionEventKinds = listOf(1, COMMENT_EVENT_KIND, 6, 16, 7)

/**
 * [self]とやりとりした相手を、件数の多い順に並べる。
 * 自分が出したイベントは`p`タグの相手、自分宛てのイベントは送り主を相手として1件ずつ数える。
 * よくないね（`-`）は数えない。
 */
internal fun rankJournalInteractions(events: Collection<NostrEvent>, self: String): List<Pair<String, Int>> {
    val counts = mutableMapOf<String, Int>()
    events.distinctBy { it.id }.forEach { event ->
        if (event.kind == 7 && event.content.trim() == "-") return@forEach
        val counterparts = if (event.pubkey == self) {
            event.tags
                .filter { it.firstOrNull() == "p" }
                .mapNotNullTo(mutableSetOf()) { it.getOrNull(1)?.takeIf(::isHexPubkey) }
        } else if (event.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == self }) {
            setOf(event.pubkey)
        } else {
            emptySet()
        }
        (counterparts - self).forEach { counts[it] = (counts[it] ?: 0) + 1 }
    }
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key to it.value }
}

private fun isHexPubkey(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
