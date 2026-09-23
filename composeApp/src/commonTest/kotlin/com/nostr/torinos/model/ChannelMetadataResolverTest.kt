package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChannelMetadataResolverTest {
    @Test
    fun creationMetadataNormalizesRecommendedRelays() {
        val result = ChannelMetadataResolver.resolve(
            channelId = CHANNEL_ID,
            createCandidates = listOf(
                event(
                    id = CHANNEL_ID,
                    kind = 40,
                    content = """{"name":"channel","relays":["WSS://Relay.Example/","wss://relay.example","https://invalid.example"]}""",
                ),
            ),
            updateCandidates = emptyList(),
        )!!

        assertEquals(40, result.effectiveEvent.kind)
        assertEquals(listOf("wss://relay.example"), result.metadata.relays)
        assertEquals(CHANNEL_ID, result.effectiveMetadata.channelId)
        assertEquals(OWNER, result.effectiveMetadata.ownerPubkey)
    }

    @Test
    fun selectsLatestValidOwnerUpdateByCreatedAtThenLowestId() {
        val wrongOwner = update("wrong-owner", createdAt = 50, pubkey = "other")
        val replyMarker = update("reply", createdAt = 50, marker = "reply")
        val malformed = update("malformed", createdAt = 50, content = "not-json")
        // created_atが同値のときはid最小のものが勝つ（NIP-01慣習、ProfileCache等と同じタイブレーク）。
        val selected = update("01", createdAt = 30)
        val higherId = update("02", createdAt = 30, marker = null)

        val result = ChannelMetadataResolver.resolve(
            CHANNEL_ID,
            listOf(creation()),
            listOf(selected, wrongOwner, higherId, malformed, replyMarker),
        )!!

        assertEquals(selected, result.effectiveEvent)
        assertEquals(setOf("wrong-owner", "reply", "malformed", "02"), result.ignoredEventIds)
    }

    @Test
    fun fallsBackToUpdateWhenCreationContentIsUnparseable() {
        val creation = creation(content = "not-json")
        val validUpdate = update("valid", createdAt = 30, content = """{"name":"renamed"}""")

        val result = ChannelMetadataResolver.resolve(
            CHANNEL_ID,
            listOf(creation),
            listOf(validUpdate),
        )!!

        assertEquals(validUpdate, result.effectiveEvent)
        assertEquals("renamed", result.metadata.name)
        assertEquals(creation, result.channelCreateEvent)
    }

    @Test
    fun returnsNullWhenNeitherCreationNorUpdatesParse() {
        val result = ChannelMetadataResolver.resolve(
            CHANNEL_ID,
            listOf(creation(content = "not-json")),
            listOf(update("invalid", content = "[")),
        )

        assertNull(result)
    }

    @Test
    fun fallsBackToCreationWhenUpdatesAreInvalid() {
        val creation = creation()
        val result = ChannelMetadataResolver.resolve(
            CHANNEL_ID,
            listOf(creation),
            listOf(update("wrong-channel", channelId = "other"), update("invalid", content = "[")),
        )!!

        assertEquals(creation, result.effectiveEvent)
        assertEquals(setOf("wrong-channel", "invalid"), result.ignoredEventIds)
    }

    @Test
    fun requiresValidMatchingCreationEvent() {
        assertNull(ChannelMetadataResolver.resolve(CHANNEL_ID, emptyList(), emptyList()))
        assertNull(
            ChannelMetadataResolver.resolve(
                CHANNEL_ID,
                listOf(creation().copy(id = "other")),
                listOf(update("update")),
            ),
        )
    }

    private fun creation(content: String = "{}") = event(id = CHANNEL_ID, kind = 40, content = content)

    private fun update(
        id: String,
        createdAt: Long = 20,
        pubkey: String = OWNER,
        channelId: String = CHANNEL_ID,
        marker: String? = "root",
        content: String = "{}",
    ) = event(
        id = id,
        kind = 41,
        createdAt = createdAt,
        pubkey = pubkey,
        content = content,
        tags = listOf(
            buildList {
                add("e")
                add(channelId)
                if (marker != null) {
                    add("wss://relay.example")
                    add(marker)
                }
            },
        ),
    )

    private fun event(
        id: String,
        kind: Int,
        createdAt: Long = 10,
        pubkey: String = OWNER,
        content: String = "{}",
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(id, pubkey, createdAt, kind, tags, content, "sig")

    private companion object {
        const val CHANNEL_ID = "channel"
        const val OWNER = "owner"
    }
}
