package com.nostr.torinos.ui.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationFollowSyncTest {
    @Test
    fun followSyncSince_isNullWithoutStoredFollowerList() {
        assertNull(followSyncSince(hasStoredFollowerList = false, lastSyncedAt = 1_000_000L))
    }

    @Test
    fun followSyncSince_isNullWithoutLastSyncTime() {
        assertNull(followSyncSince(hasStoredFollowerList = true, lastSyncedAt = null))
    }

    @Test
    fun followSyncSince_subtractsOverlapFromLastSync() {
        assertEquals(
            1_000_000L - FOLLOW_SYNC_OVERLAP_SECONDS,
            followSyncSince(hasStoredFollowerList = true, lastSyncedAt = 1_000_000L),
        )
    }

    @Test
    fun followSyncSince_neverGoesNegative() {
        assertEquals(0L, followSyncSince(hasStoredFollowerList = true, lastSyncedAt = 10L))
    }

    @Test
    fun followFilter_requestsContactListsMentioningOwnPubkey() {
        val filter = followFilter(ownPubkey = "me", limit = 100, since = 123L)
        assertEquals(listOf(3), filter.kinds)
        assertEquals(listOf("me"), filter.pTags)
        assertEquals(123L, filter.since)
        assertEquals(100, filter.limit)
    }

    @Test
    fun followFilter_withoutSinceFetchesLatest() {
        assertNull(followFilter(ownPubkey = "me", limit = 100, since = null).since)
    }
}
