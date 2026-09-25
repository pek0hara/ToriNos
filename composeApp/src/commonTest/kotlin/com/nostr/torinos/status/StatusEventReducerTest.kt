package com.nostr.torinos.status

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusEventReducerTest {
    @Test
    fun newerCreatedAtWinsRegardlessOfArrivalOrder() {
        val old = entry(id = "b", createdAt = 10, content = "old")
        val latest = entry(id = "c", createdAt = 20, content = "latest")

        val forward = listOf(old, latest).fold(StatusSnapshot(), StatusEventReducer::reduce)
        val reverse = listOf(latest, old).fold(StatusSnapshot(), StatusEventReducer::reduce)

        assertEquals("latest", forward.latestByAddress.values.single().content)
        assertEquals(forward, reverse)
    }

    @Test
    fun smallerIdWinsWhenCreatedAtIsEqual() {
        val largerId = entry(id = "f", createdAt = 10, content = "larger")
        val smallerId = entry(id = "a", createdAt = 10, content = "smaller")

        val forward = listOf(largerId, smallerId).fold(StatusSnapshot(), StatusEventReducer::reduce)
        val reverse = listOf(smallerId, largerId).fold(StatusSnapshot(), StatusEventReducer::reduce)

        assertEquals("smaller", forward.latestByAddress.values.single().content)
        assertEquals(forward, reverse)
    }

    @Test
    fun blankLatestVersionPreventsOlderStatusFromReappearing() {
        val visible = entry(id = "b", createdAt = 10, content = "visible")
        val deletion = entry(id = "a", createdAt = 20, content = "")
        val snapshot = listOf(visible, deletion, visible)
            .fold(StatusSnapshot(), StatusEventReducer::reduce)

        assertTrue(StatusEventReducer.activeStatuses(snapshot, nowEpochSeconds = 20).isEmpty())
        assertEquals(deletion, snapshot.latestByAddress.values.single())
    }

    @Test
    fun expirationMuteAndCategorySelectionAreAppliedDuringDerivation() {
        val general = entry(id = "a", createdAt = 30, content = "general")
        val music = entry(id = "b", createdAt = 20, content = "music", identifier = "music")
        val expired = entry(
            id = "c",
            pubkey = "3".repeat(64),
            createdAt = 10,
            content = "expired",
            expiration = 100,
        )
        val snapshot = listOf(general, music, expired)
            .fold(StatusSnapshot(), StatusEventReducer::reduce)

        assertTrue(StatusEventReducer.visibleStatuses(snapshot, 100, emptySet()).isEmpty())
        assertEquals(
            listOf("music"),
            StatusEventReducer.visibleStatuses(
                snapshot,
                nowEpochSeconds = 100,
                selectedCategories = setOf("music"),
            ).map(StatusEntry::content),
        )
        assertTrue(
            StatusEventReducer.visibleStatuses(
                snapshot,
                nowEpochSeconds = 100,
                selectedCategories = setOf("general"),
                mutedPubkeys = setOf(general.address.pubkey),
            ).isEmpty(),
        )
    }

    @Test
    fun activeStatusReturnsOnlyActiveEntryAtRequestedAddress() {
        val address = StatusAddress("2".repeat(64), GENERAL_STATUS_IDENTIFIER)
        val other = entry(
            id = "b",
            pubkey = "3".repeat(64),
            createdAt = 20,
            content = "other",
        )
        val target = entry(
            id = "a",
            createdAt = 10,
            content = "target",
            expiration = 101,
        )
        val snapshot = listOf(other, target).fold(StatusSnapshot(), StatusEventReducer::reduce)

        assertEquals(target, StatusEventReducer.activeStatus(snapshot, address, 100))
        assertNull(StatusEventReducer.activeStatus(snapshot, address, 101))
        assertNull(StatusEventReducer.activeStatus(snapshot, other.address.copy(identifier = "music"), 100))
    }

    @Test
    fun activeStatusKeepsBlankTombstoneWhileReturningNull() {
        val visible = entry(id = "b", createdAt = 10, content = "visible")
        val deletion = entry(id = "a", createdAt = 20, content = "")
        val snapshot = listOf(visible, deletion, visible)
            .fold(StatusSnapshot(), StatusEventReducer::reduce)

        assertNull(StatusEventReducer.activeStatus(snapshot, deletion.address, 20))
        assertEquals(deletion, snapshot.latestByAddress[deletion.address])
    }

    private fun entry(
        id: String,
        pubkey: String = "2".repeat(64),
        createdAt: Long,
        content: String,
        identifier: String = GENERAL_STATUS_IDENTIFIER,
        expiration: Long? = null,
    ): StatusEntry {
        val event = NostrEvent(
            id = id.padEnd(64, id.first()),
            pubkey = pubkey,
            createdAt = createdAt,
            kind = STATUS_EVENT_KIND,
            tags = buildList {
                add(listOf("d", identifier))
                if (expiration != null) add(listOf("expiration", expiration.toString()))
            },
            content = content,
            sig = "4".repeat(128),
        )
        return checkNotNull(StatusEventCodec.parse(event))
    }
}
