package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.RelayEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChannelReplyContextTest {
    private val entries = listOf(RelayEntry("wss://mine", enabled = true))
    private val message = NostrEvent("parent", "author", 1, 42, listOf(listOf("e", "channel", "", "root")), "hi", "sig")

    @Test
    fun contextSendsToRecommendedPlusUserWritesAndHintsFirstRecommended() {
        val context = ChannelReplyContextBuilder.build(listOf("wss://rec/"), entries)
        assertEquals(setOf("wss://rec", "wss://mine"), context.initialRelayUrls)
        assertEquals(listOf("wss://rec"), context.recommendedRelayUrls)
        assertEquals("wss://rec", context.primaryHint)
    }

    @Test
    fun replyTargetPrefersObservedRelayThenChannelHint() {
        val context = ChannelReplyContextBuilder.build(listOf("wss://rec"), entries)
        val observed = ChannelReplyContextBuilder.replyTarget(message, "channel", "wss://Seen.example/", context)!!
        assertEquals(
            listOf(
                listOf("e", "channel", "wss://rec", "root"),
                listOf("e", "parent", "wss://seen.example", "reply"),
                listOf("p", "author", "wss://seen.example"),
            ),
            observed.tags(),
        )
        val unknown = ChannelReplyContextBuilder.replyTarget(message, "channel", null, context)!!
        assertEquals(listOf("e", "parent", "wss://rec", "reply"), unknown.tags()[1])
    }

    @Test
    fun withoutRecommendedRelaysTheUserWriteRelayIsTheHint() {
        val context = ChannelReplyContextBuilder.build(emptyList(), entries)
        assertEquals("wss://mine", context.primaryHint)
        assertEquals(setOf("wss://mine"), context.initialRelayUrls)
    }

    @Test
    fun nonChannelMessagesAreNotReplyTargets() {
        val context = ChannelReplyContextBuilder.build(emptyList(), entries)
        assertNull(ChannelReplyContextBuilder.replyTarget(message.copy(kind = 1), "channel", null, context))
    }
}
