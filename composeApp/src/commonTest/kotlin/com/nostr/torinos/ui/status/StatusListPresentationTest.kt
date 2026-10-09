package com.nostr.torinos.ui.status

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.status.GENERAL_STATUS_IDENTIFIER
import com.nostr.torinos.status.MUSIC_STATUS_IDENTIFIER
import com.nostr.torinos.status.STATUS_EVENT_KIND
import com.nostr.torinos.status.StatusAddress
import com.nostr.torinos.status.StatusEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusListPresentationTest {
    @Test
    fun groupStatusesByUser_mergesCategoriesPerUserAndOrdersByLatest() {
        val statuses = listOf(
            entry("bob", MUSIC_STATUS_IDENTIFIER, createdAt = 300),
            entry("alice", GENERAL_STATUS_IDENTIFIER, createdAt = 200),
            entry("bob", "coding", createdAt = 150),
            entry("bob", GENERAL_STATUS_IDENTIFIER, createdAt = 100),
        )

        val groups = groupStatusesByUser(statuses, excludePubkey = null)

        assertEquals(listOf("bob", "alice"), groups.map { it.pubkey })
        assertEquals(
            listOf(GENERAL_STATUS_IDENTIFIER, MUSIC_STATUS_IDENTIFIER, "coding"),
            groups.first().statuses.map { it.identifier },
        )
        assertEquals(300L, groups.first().latestCreatedAt)
    }

    @Test
    fun groupStatusesByUser_excludesOwnStatuses() {
        val statuses = listOf(
            entry("me", GENERAL_STATUS_IDENTIFIER, createdAt = 300),
            entry("alice", GENERAL_STATUS_IDENTIFIER, createdAt = 200),
        )

        assertEquals(listOf("alice"), groupStatusesByUser(statuses, excludePubkey = "me").map { it.pubkey })
    }

    @Test
    fun customStatusTagLabel_shortensLongIdentifiers() {
        assertEquals("coding", customStatusTagLabel("coding"))
        assertEquals("3322726b…", customStatusTagLabel("3322726b-c663-4ec6-b133-50ffa6eaa4c7"))
    }

    @Test
    fun statusRelativeTimeText_usesRelativeUnitsWithinAWeek() {
        assertEquals("たった今", statusRelativeTimeText(NOW - 59L, NOW))
        assertEquals("1分前", statusRelativeTimeText(NOW - 60L, NOW))
        assertEquals("23時間前", statusRelativeTimeText(NOW - 86399L, NOW))
        assertEquals("6日前", statusRelativeTimeText(NOW - 7L * 86400L + 1L, NOW))
        assertEquals("たった今", statusRelativeTimeText(NOW + 30L, NOW))
    }

    @Test
    fun isStaleStatus_afterOneDay() {
        assertFalse(isStaleStatus(NOW - 86399L, NOW))
        assertTrue(isStaleStatus(NOW - 86400L, NOW))
    }

    @Test
    fun referenceUrlDisplayHost_keepsOnlyDomain() {
        assertEquals("koteitan.github.io", referenceUrlDisplayHost("https://koteitan.github.io/countdown/"))
        assertEquals("example.com", referenceUrlDisplayHost("https://www.example.com?x=1"))
        assertEquals("spotify:track:1", referenceUrlDisplayHost("spotify:track:1"))
    }

    private fun entry(pubkey: String, identifier: String, createdAt: Long): StatusEntry =
        StatusEntry(
            event = NostrEvent(
                id = "$pubkey-$identifier",
                pubkey = pubkey,
                createdAt = createdAt,
                kind = STATUS_EVENT_KIND,
                tags = listOf(listOf("d", identifier)),
                content = "status",
                sig = "",
            ),
            address = StatusAddress(pubkey, identifier),
            content = "status",
            expiration = null,
            referenceUrls = emptyList(),
            customEmojis = emptyMap(),
        )

    private companion object {
        const val NOW = 1_700_000_000L
    }
}
