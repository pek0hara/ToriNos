package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent

/** 保持するイベントと、表示前の履歴ページを同じ所有者へまとめる。 */
internal class FeedTimelineModel {
    val sortTimes = mutableMapOf<String, Long>()
    val held = HeldFeedEvents(Int.MAX_VALUE) { event -> sortTimes[event.id] ?: event.createdAt }
    val canonical = linkedMapOf<String, NostrEvent>()
    val uncommittedHistoryIds = linkedSetOf<String>()
}
