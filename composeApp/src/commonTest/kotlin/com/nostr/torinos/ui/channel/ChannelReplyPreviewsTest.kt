package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelReplyPreviewsTest {
    private fun reply(id: String, parent: String, time: Long = 1, channel: String = "channel") =
        NostrEvent(
            id, "author-$id", time, 42,
            listOf(listOf("e", channel, "", "root"), listOf("e", parent, "", "reply")),
            "message $id", "sig",
        )

    @Test
    fun groupsDirectRepliesAndDeduplicatesRelayDeliveriesInTimeOrder() {
        val first = reply("first", "parent", 1)
        val second = reply("second", "parent", 2)
        val nested = reply("nested", "first", 3)
        val previews = channelReplyPreviews("channel", listOf(second, first, first, nested))
        assertEquals(listOf(first, second), previews["parent"])
        assertEquals(listOf(nested), previews["first"])
    }

    @Test
    fun excludesOtherChannelsAndRootMessages() {
        val direct = reply("direct", "parent")
        val root = direct.copy(id = "root", tags = listOf(listOf("e", "channel", "", "root")))
        assertEquals(
            mapOf("parent" to listOf(direct)),
            channelReplyPreviews("channel", listOf(root, direct, reply("other", "parent", channel = "other"))),
        )
    }

    @Test
    fun appliesHiddenMutedAndNgWordRulesToPreviews() {
        val visible = reply("visible", "parent")
        assertEquals(
            mapOf("parent" to listOf(visible)),
            channelReplyPreviews(
                "channel",
                listOf(visible, reply("hidden", "parent"), reply("muted", "parent"), reply("blocked", "parent")),
                hiddenIds = setOf("hidden"),
                mutedPubkeys = setOf("author-muted"),
                ngWords = listOf("BLOCKED"),
            ),
        )
    }
}
