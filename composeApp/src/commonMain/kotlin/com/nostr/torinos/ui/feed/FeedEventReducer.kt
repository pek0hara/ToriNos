package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent

/** Feedの表示順と重複排除だけを担う純粋Reducer。 */
internal object FeedEventReducer {
    fun sort(events: List<NostrEvent>, sortTimes: Map<String, Long>): List<NostrEvent> =
        events.distinctBy { it.id }.sortedWith(
            compareByDescending<NostrEvent> { sortTimes[it.id] ?: it.createdAt }
                .thenByDescending { it.id },
        )

    fun insert(
        events: List<NostrEvent>,
        event: NostrEvent,
        sortTimes: Map<String, Long>,
    ): List<NostrEvent> = if (events.any { it.id == event.id }) {
        events
    } else {
        sort(events + event, sortTimes)
    }
}
