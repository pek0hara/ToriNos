package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.ChannelMetadataResolver
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.ChannelLocalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChannelInfoTest {
    private val create = NostrEvent(
        "channel", "owner", 100, 40, listOf(listOf("client", "ToriNos")),
        """{"name":"old","relays":["wss://a"]}""", "sig",
    )
    private val update = NostrEvent(
        "update", "owner", 200, 41, listOf(listOf("e", "channel", "wss://a", "root"), listOf("client", "Other")),
        """{"name":"new","about":"x","relays":["wss://b"]}""", "sig",
    )

    @Test
    fun infoFromResolutionShowsKind41SourceAndBothClients() {
        val resolution = ChannelMetadataResolver.resolve("channel", listOf(create), listOf(update))!!
        val info = ChannelInfo.from(resolution)
        assertEquals("new", info.meta.name)
        assertEquals(listOf("wss://b"), info.meta.relays)
        assertEquals(100L, info.createdAt)
        assertEquals(200L, info.updatedAt)
        assertEquals(41, info.sourceKind)
        assertEquals("update", info.sourceEventId)
        assertEquals("ToriNos", info.createClient)
        assertEquals("Other", info.updateClient)
    }

    @Test
    fun infoFromKind40OnlyHasNoUpdate() {
        val info = ChannelInfo.from(ChannelMetadataResolver.resolve("channel", listOf(create), emptyList())!!)
        assertEquals(40, info.sourceKind)
        assertNull(info.updatedAt)
        assertNull(info.updateClient)
    }

    @Test
    fun infoFromLocalStateForProvisionalDisplay() {
        val state = ChannelLocalState(
            channelId = "channel",
            ownerPubkey = "owner",
            channelCreatedAt = 100,
            metadataEventId = "update",
            metadataKind = 41,
            metadataCreatedAt = 200,
            meta = ChannelMeta(name = "cached"),
        )
        val info = ChannelInfo.from(state)
        assertEquals("cached", info.meta.name)
        assertEquals(200L, info.updatedAt)
        assertEquals(41, info.sourceKind)
        assertNull(ChannelInfo.from(state.copy(ownerPubkey = "", metadataEventId = "")).sourceKind)
    }
}
