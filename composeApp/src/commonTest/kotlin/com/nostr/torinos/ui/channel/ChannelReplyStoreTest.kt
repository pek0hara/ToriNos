package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelReplyStoreTest {
    private fun reply(id: String, parent: String = "parent") = NostrEvent(
        id, "author", 1, 42,
        listOf(listOf("e", "channel", "", "root"), listOf("e", parent, "", "reply")),
        "reply $id", "sig",
    )

    @Test
    fun historyAndReplySubscriptionDeliveriesShareCountsAndPreviews() {
        val store = ChannelReplyStore("channel")
        val event = reply("reply")
        assertTrue(store.receive(event))
        assertFalse(store.receive(event))
        assertEquals(mapOf("parent" to 1), store.counts)
        assertEquals(mapOf("parent" to listOf(event)), channelReplyPreviews("channel", store.events))
    }

    @Test
    fun boundedBodyCacheDoesNotCountEvictedRepliesTwice() {
        val store = ChannelReplyStore("channel", maxCachedEvents = 2)
        val first = reply("first")
        store.receive(first)
        store.receive(reply("second"))
        store.receive(reply("third"))
        assertEquals(listOf("second", "third"), store.events.map { it.id })
        assertEquals(3, store.counts["parent"])
        assertTrue(store.receive(first))
        assertEquals(listOf("third", "first"), store.events.map { it.id })
        assertEquals(3, store.counts["parent"])
    }

    @Test
    fun deletingReplyUpdatesCountsAndLateRelayEchoCannotRestoreIt() {
        val store = ChannelReplyStore("channel")
        val event = reply("reply")
        store.receive(event)
        store.remove(event.id)
        store.remove(event.id)
        assertEquals(emptyMap(), store.counts)
        assertEquals(emptyList(), store.events.toList())
        assertFalse(store.receive(event))
    }

    @Test
    fun deletingEvictedReplyStillDecrementsItsParentCount() {
        val store = ChannelReplyStore("channel", maxCachedEvents = 1)
        store.receive(reply("first"))
        store.receive(reply("second"))
        store.remove("first")
        assertEquals(1, store.counts["parent"])
        assertEquals(listOf("second"), store.events.map { it.id })
    }

    @Test
    fun deletionBeforeHistoryDeliveryPreventsReplyFromReappearing() {
        val store = ChannelReplyStore("channel")
        store.remove("deleted")
        assertFalse(store.receive(reply("deleted")))
        assertTrue(store.receive(reply("remaining")))
        assertEquals(mapOf("parent" to 1), store.counts)
        assertEquals(listOf("remaining"), store.events.map { it.id })
    }

    @Test
    fun rootMessagesAndOtherChannelsDoNotChangeReplyCounts() {
        val store = ChannelReplyStore("channel")
        val event = reply("reply")
        assertFalse(store.receive(event.copy(tags = listOf(listOf("e", "channel", "", "root")))))
        assertFalse(store.receive(event.copy(tags = listOf(listOf("e", "other", "", "root"), listOf("e", "parent", "", "reply")))))
        assertEquals(emptyMap(), store.counts)
    }
}
