package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveActivityTest {
    @Test
    fun toLiveActivityMeta_infersPlannedFromFutureStartsWhenStatusMissing() {
        val event = liveEvent(
            createdAt = 100,
            tags = listOf(
                listOf("d", "future"),
                listOf("title", "Future live"),
                listOf("starts", "200"),
            ),
        )

        val meta = event.toLiveActivityMeta(now = 150)

        assertEquals(LiveActivityStatus.Planned, meta?.status)
    }

    @Test
    fun toLiveActivityMeta_infersEndedFromPastEndsWhenStatusMissing() {
        val event = liveEvent(
            createdAt = 100,
            tags = listOf(
                listOf("d", "past"),
                listOf("starts", "120"),
                listOf("ends", "180"),
            ),
        )

        val meta = event.toLiveActivityMeta(now = 200)

        assertEquals(LiveActivityStatus.Ended, meta?.status)
    }

    @Test
    fun toLiveActivityMeta_keepsExplicitPlannedStatus() {
        val event = liveEvent(
            createdAt = 100,
            tags = listOf(
                listOf("d", "explicit"),
                listOf("status", "planned"),
            ),
        )

        val meta = event.toLiveActivityMeta(now = 200)

        assertEquals(LiveActivityStatus.Planned, meta?.status)
    }
}

private fun liveEvent(
    createdAt: Long,
    tags: List<List<String>>,
): NostrEvent = NostrEvent(
    id = "id-$createdAt",
    pubkey = "pubkey",
    createdAt = createdAt,
    kind = NIP53_LIVE_ACTIVITY_KIND,
    tags = tags,
    content = "",
    sig = "sig",
)
