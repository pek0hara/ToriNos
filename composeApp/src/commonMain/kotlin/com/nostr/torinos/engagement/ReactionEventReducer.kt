package com.nostr.torinos.engagement

import com.nostr.torinos.model.NostrEvent

/** フィードとポスト詳細のリアクションプレビューで共有するイベント更新処理。 */
internal object ReactionEventReducer {
    fun add(events: List<NostrEvent>, event: NostrEvent): List<NostrEvent> =
        if (events.any { it.id == event.id }) events else events + event

    fun remove(events: List<NostrEvent>, eventId: String): List<NostrEvent> =
        events.filterNot { it.id == eventId }

    fun latestByPubkey(events: List<NostrEvent>): Map<String, NostrEvent> =
        events.groupBy { it.pubkey }.mapValues { (_, reactions) ->
            reactions.maxWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
        }
}
