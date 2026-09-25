package com.nostr.torinos.ui.profile

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.status.StatusSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test
    fun activeGeneralStatus_usesCaseSensitiveIdentifier() {
        val status = statusEvent(
            content = "uppercase category",
            tags = listOf(listOf("d", "General")),
        ).toActiveGeneralStatus()

        assertNull(status)
    }

    @Test
    fun activeGeneralStatus_excludesExpirationAtCurrentSecond() {
        val status = statusEvent(
            content = "expired",
            tags = listOf(
                listOf("d", "general"),
                listOf("expiration", "1000"),
            ),
        ).toActiveGeneralStatus(nowEpochSeconds = 1000)

        assertNull(status)
    }

    @Test
    fun reductionRejectsEventsOutsideExpectedGeneralAddress() {
        val initial = StatusSnapshot()

        assertNull(
            reduceProfileGeneralStatus(
                initial,
                statusEvent(content = "wrong kind", tags = listOf(listOf("d", "general")), kind = 1),
                EXPECTED_PUBKEY,
            ),
        )
        assertNull(
            reduceProfileGeneralStatus(
                initial,
                statusEvent(
                    content = "wrong author",
                    tags = listOf(listOf("d", "general")),
                    pubkey = OTHER_PUBKEY,
                ),
                EXPECTED_PUBKEY,
            ),
        )
        assertNull(
            reduceProfileGeneralStatus(
                initial,
                statusEvent(content = "wrong category", tags = listOf(listOf("d", "music"))),
                EXPECTED_PUBKEY,
            ),
        )
    }

    @Test
    fun reductionUsesSameTimestampIdOrderingForReceivedAndPublishedEvents() {
        val current = assertNotNull(
            reduceProfileGeneralStatus(
                StatusSnapshot(),
                statusEvent(
                    id = "m",
                    createdAt = 100,
                    content = "current",
                    tags = listOf(listOf("d", "general")),
                ),
                EXPECTED_PUBKEY,
                nowEpochSeconds = 100,
            ),
        )
        val winner = assertNotNull(
            reduceProfileGeneralStatus(
                current.snapshot,
                statusEvent(
                    id = "a",
                    createdAt = 100,
                    content = "winner",
                    tags = listOf(listOf("d", "general")),
                ),
                EXPECTED_PUBKEY,
                nowEpochSeconds = 100,
            ),
        )

        assertEquals("winner", winner.generalStatus?.content)
        assertNull(
            reduceProfileGeneralStatus(
                winner.snapshot,
                statusEvent(
                    id = "z",
                    createdAt = 100,
                    content = "published but non-canonical",
                    tags = listOf(listOf("d", "general")),
                ),
                EXPECTED_PUBKEY,
                nowEpochSeconds = 100,
            ),
        )
    }

    @Test
    fun reductionKeepsDeletionAsLatestAndRejectsOlderVisibleEvent() {
        val visible = assertNotNull(
            reduceProfileGeneralStatus(
                StatusSnapshot(),
                statusEvent(
                    id = "b",
                    createdAt = 10,
                    content = "visible",
                    tags = listOf(listOf("d", "general")),
                ),
                EXPECTED_PUBKEY,
                nowEpochSeconds = 20,
            ),
        )
        val deleted = assertNotNull(
            reduceProfileGeneralStatus(
                visible.snapshot,
                statusEvent(
                    id = "a",
                    createdAt = 20,
                    content = "",
                    tags = listOf(listOf("d", "general")),
                ),
                EXPECTED_PUBKEY,
                nowEpochSeconds = 20,
            ),
        )

        assertNull(deleted.generalStatus)
        assertNull(
            reduceProfileGeneralStatus(
                deleted.snapshot,
                statusEvent(
                    id = "c",
                    createdAt = 15,
                    content = "stale",
                    tags = listOf(listOf("d", "general")),
                ),
                EXPECTED_PUBKEY,
                nowEpochSeconds = 20,
            ),
        )
    }

    private fun statusEvent(
        id: String = "0",
        pubkey: String = EXPECTED_PUBKEY,
        createdAt: Long = Clock.System.now().epochSeconds,
        kind: Int = PROFILE_STATUS_KIND,
        content: String,
        tags: List<List<String>>,
    ) = NostrEvent(
        id = id.repeat(64),
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )

    private companion object {
        val EXPECTED_PUBKEY = "1".repeat(64)
        val OTHER_PUBKEY = "2".repeat(64)
    }
}
