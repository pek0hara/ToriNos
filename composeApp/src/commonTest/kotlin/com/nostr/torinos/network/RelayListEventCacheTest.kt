package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayListEventCacheTest {
    @Test
    fun olderEventArrivingLaterDoesNotReplaceLatestEvent() {
        val pubkey = "relay-list-cache-newest-test"
        val newer = relayListEvent(pubkey, id = "newer", createdAt = 200, relayCount = 3)
        val older = relayListEvent(pubkey, id = "older", createdAt = 100, relayCount = 1)

        assertEquals(newer, RelayListEventCache.putEvent(newer))
        assertEquals(newer, RelayListEventCache.putEvent(older))
        assertEquals(newer, RelayListEventCache.get(pubkey))
    }

    @Test
    fun eventIdBreaksCreatedAtTieDeterministically() {
        val pubkey = "relay-list-cache-tie-test"
        val largerId = relayListEvent(pubkey, id = "ff", createdAt = 200, relayCount = 3)
        val smallerId = relayListEvent(pubkey, id = "00", createdAt = 200, relayCount = 1)

        RelayListEventCache.putEvent(largerId)

        assertEquals(smallerId, RelayListEventCache.putEvent(smallerId))
        assertEquals(smallerId, RelayListEventCache.get(pubkey))
    }

    private fun relayListEvent(
        pubkey: String,
        id: String,
        createdAt: Long,
        relayCount: Int,
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = 10002,
        tags = List(relayCount) { index -> listOf("r", "wss://relay-$index.example") },
        content = "",
        sig = "",
    )
}
