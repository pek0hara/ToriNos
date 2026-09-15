package com.nostr.torinos.ui.profile

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

class ProfileGeneralStatusTest {
    @Test
    fun activeGeneralStatus_preservesExpirationAndReferenceUrl() {
        val expiration = Clock.System.now().epochSeconds + 3_600
        val status = statusEvent(
            content = "開発中です",
            tags = listOf(
                listOf("d", "general"),
                listOf("expiration", expiration.toString()),
                listOf("r", "https://example.com/status"),
            ),
        ).toActiveGeneralStatus()

        assertEquals("開発中です", status?.content)
        assertEquals(expiration, status?.expiration)
        assertEquals("https://example.com/status", status?.referenceUrl)
    }

    @Test
    fun activeGeneralStatus_ignoresAnotherCategory() {
        val status = statusEvent(
            content = "音楽を聴いています",
            tags = listOf(listOf("d", "music")),
        ).toActiveGeneralStatus()

        assertNull(status)
    }

    private fun statusEvent(
        content: String,
        tags: List<List<String>>,
    ) = NostrEvent(
        id = "0".repeat(64),
        pubkey = "1".repeat(64),
        createdAt = Clock.System.now().epochSeconds,
        kind = PROFILE_STATUS_KIND,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )
}
