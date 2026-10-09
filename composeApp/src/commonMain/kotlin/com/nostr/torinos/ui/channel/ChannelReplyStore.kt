package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NoteContext

/** 履歴取得と返信購読を集約する。本文の保持上限と件数の重複排除は独立させる。 */
internal class ChannelReplyStore(channelId: String, private val maxCachedEvents: Int = 1000) {
    private val context = NoteContext.Channel(channelId)
    private val parentsByReplyId = mutableMapOf<String, String>()
    private val deletedIds = mutableSetOf<String>()
    private val cachedEvents = linkedMapOf<String, NostrEvent>()
    var counts: Map<String, Int> = emptyMap()
        private set
    val events: Collection<NostrEvent> get() = cachedEvents.values

    init {
        require(maxCachedEvents > 0)
    }

    fun receive(event: NostrEvent): Boolean {
        if (event.id in deletedIds || !context.matches(event)) return false
        val parent = context.replyTargetId(event) ?: return false
        if (event.id in cachedEvents) return false
        if (parentsByReplyId.put(event.id, parent) == null) {
            counts = counts + (parent to (counts[parent] ?: 0) + 1)
        }
        cachedEvents[event.id] = event
        while (cachedEvents.size > maxCachedEvents) cachedEvents.remove(cachedEvents.keys.first())
        return true
    }

    fun remove(eventId: String) {
        deletedIds.add(eventId)
        cachedEvents.remove(eventId)
        val parent = parentsByReplyId.remove(eventId) ?: return
        val count = (counts[parent] ?: 0) - 1
        counts = if (count > 0) counts + (parent to count) else counts - parent
    }
}
