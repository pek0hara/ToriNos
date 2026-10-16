package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent

internal data class FeedWindow(val events: List<NostrEvent>, val hasOlder: Boolean, val hasNewer: Boolean)

internal fun planFeedWindow(
    events: List<NostrEvent>,
    oldestVisibleAt: Long?,
    sortTimes: Map<String, Long>,
    anchor: String?,
    maxEvents: Int = FeedController.MAX_TIMELINE_EVENTS,
): FeedWindow {
    require(maxEvents > 0)
    val revealed = if (oldestVisibleAt == null) events else events.filter { (sortTimes[it.id] ?: it.createdAt) >= oldestVisibleAt }
    val start = anchor?.let { id -> revealed.indexOfFirst { it.id == id }.coerceAtLeast(0) } ?: 0
    val visible = revealed.drop(start).take(maxEvents)
    return FeedWindow(events = visible, hasOlder = (start + visible.size < revealed.size), hasNewer = (start > 0))
}
