package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelEventTagsTest {
    @Test
    fun metadataContainsRootAndUniqueNonBlankCategories() {
        assertEquals(
            listOf(
                listOf("e", "channel", "wss://relay.example", "root"),
                listOf("t", "nostr"),
                listOf("t", "chat"),
            ),
            ChannelEventTags.metadata(
                channelId = "channel",
                relayHint = "wss://relay.example",
                categories = listOf(" nostr ", "", "nostr", "chat"),
            ),
        )
    }

    @Test
    fun markedRootKeepsMarkerPositionWithoutHint() {
        assertEquals(
            listOf(listOf("e", "channel", "", "root")),
            ChannelEventTags.rootMessage("channel", null),
        )
    }

    @Test
    fun replyPrefersObservedParentRelayAndIncludesAuthor() {
        assertEquals(
            listOf(
                listOf("e", "channel", "wss://channel.example", "root"),
                listOf("e", "parent", "wss://observed.example", "reply"),
                listOf("p", "author", "wss://author.example"),
            ),
            ChannelEventTags.replyMessage(
                channelId = "channel",
                channelRelayHint = "wss://channel.example",
                parent = ReplyEventReference(
                    id = "parent",
                    kind = 42,
                    pubkey = "author",
                    relayUrl = "wss://observed.example",
                    pubkeyRelayUrl = "wss://author.example",
                ),
            ),
        )
    }

    @Test
    fun replyFallsBackToChannelHintForParentAndPubkey() {
        assertEquals(
            listOf(
                listOf("e", "channel", "wss://channel.example", "root"),
                listOf("e", "parent", "wss://channel.example", "reply"),
                listOf("p", "author", "wss://channel.example"),
            ),
            ChannelEventTags.replyMessage(
                channelId = "channel",
                channelRelayHint = "wss://channel.example",
                parent = ReplyEventReference(id = "parent", kind = 42, pubkey = "author"),
            ),
        )
    }

    @Test
    fun channelReplyTargetUsesTheSameTagBuilder() {
        val target = ReplyTarget.Channel(
            channelId = "channel",
            parent = ReplyEventReference(id = "parent", kind = 42, pubkey = "author", relayUrl = "wss://seen"),
            channelRelayUrl = "wss://rec",
        )
        assertEquals(
            listOf(
                listOf("e", "channel", "wss://rec", "root"),
                listOf("e", "parent", "wss://seen", "reply"),
                listOf("p", "author", "wss://seen"),
            ),
            target.tags(),
        )
    }
}
