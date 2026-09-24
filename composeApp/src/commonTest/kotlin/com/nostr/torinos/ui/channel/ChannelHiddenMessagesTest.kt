package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelHiddenMessagesTest {
    private fun hide(id: String, target: String, by: String = "me", at: Long = 1) =
        NostrEvent(id, by, at, 43, listOf(listOf("e", target, "wss://r")), "{\"reason\":\"\"}", "sig")

    private fun deletion(id: String, target: String, by: String = "me") =
        NostrEvent(id, by, 2, 5, listOf(listOf("e", target), listOf("k", "43")), "", "sig")

    @Test
    fun onlyOwnHideEventsApply() {
        val hidden = ChannelHiddenMessages.hiddenTargets("me", listOf(hide("h1", "m1"), hide("h2", "m2", by = "other")), emptyList())
        assertEquals(mapOf("m1" to "h1"), hidden)
    }

    @Test
    fun ownDeletionOfTheHideEventUnhides() {
        val hidden = ChannelHiddenMessages.hiddenTargets(
            "me",
            listOf(hide("h1", "m1"), hide("h2", "m2")),
            listOf(deletion("d1", "h1"), deletion("d2", "h2", by = "other")),
        )
        assertEquals(mapOf("m2" to "h2"), hidden)
    }

    @Test
    fun latestHideEventWinsWhenTheSameMessageWasHiddenTwice() {
        val hidden = ChannelHiddenMessages.hiddenTargets("me", listOf(hide("old", "m1", at = 1), hide("new", "m1", at = 5)), emptyList())
        assertEquals(mapOf("m1" to "new"), hidden)
    }

    @Test
    fun tagsCarryHintAndKindMarker() {
        assertEquals(listOf(listOf("e", "m1", "wss://r")), ChannelHiddenMessages.hideTags("m1", "wss://r"))
        assertEquals(listOf(listOf("e", "m1")), ChannelHiddenMessages.hideTags("m1", null))
        assertEquals(listOf(listOf("e", "h1"), listOf("k", "43")), ChannelHiddenMessages.unhideTags("h1"))
    }

    @Test
    fun bulkUnhideIsOneDeletionWithAllHideEvents() {
        assertEquals(
            listOf(listOf("e", "h1"), listOf("e", "h2"), listOf("k", "43")),
            ChannelHiddenMessages.unhideTags(listOf("h1", "h2", "h1")),
        )
        val hidden = ChannelHiddenMessages.hiddenTargets(
            "me",
            listOf(hide("h1", "m1"), hide("h2", "m2")),
            listOf(NostrEvent("d", "me", 3, 5, ChannelHiddenMessages.unhideTags(listOf("h1", "h2")), "", "sig")),
        )
        assertEquals(emptyMap(), hidden)
    }

    @Test
    fun storeIsScopedPerAccount() {
        ChannelHiddenMessageStore.update("acct-a", setOf("m1"))
        assertEquals(setOf("m1"), ChannelHiddenMessageStore.observe("acct-a").value)
        assertEquals(emptySet(), ChannelHiddenMessageStore.observe("acct-b").value)
    }
}
