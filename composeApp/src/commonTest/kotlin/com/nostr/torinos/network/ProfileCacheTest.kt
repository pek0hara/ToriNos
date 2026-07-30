package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileCacheTest {

    @Test
    fun putEvent_returnsNewestCachedProfile_whenOlderEventArrivesLater() {
        val pubkey = "profile-cache-newest-test"
        val newer = profileEvent(
            pubkey = pubkey,
            createdAt = 200,
            picture = "https://example.com/new.jpg",
        )
        val older = profileEvent(
            pubkey = pubkey,
            createdAt = 100,
            picture = "https://example.com/old.jpg",
        )

        assertEquals("https://example.com/new.jpg", ProfileCache.putEvent(newer)?.picture)
        assertEquals("https://example.com/new.jpg", ProfileCache.putEvent(older)?.picture)
        assertEquals("https://example.com/new.jpg", ProfileCache.get(pubkey)?.picture)
    }

    private fun profileEvent(
        pubkey: String,
        createdAt: Long,
        picture: String,
    ) = NostrEvent(
        id = "$pubkey-$createdAt",
        pubkey = pubkey,
        createdAt = createdAt,
        kind = 0,
        tags = emptyList(),
        content = """{"picture":"$picture"}""",
        sig = "",
    )
}
