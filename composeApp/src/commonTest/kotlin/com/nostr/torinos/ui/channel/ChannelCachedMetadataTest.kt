package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelCachedMetadataTest {
    private val cachedKind41 = CachedMetadataSource(ownerPubkey = "owner", eventId = "u41", createdAt = 200)

    @Test
    fun kind40ArrivingBeforeKind41DoesNotReplaceNewerCachedMetadata() {
        assertTrue(shouldKeepCachedMetadata(cachedKind41, "owner", event("c40", 100, 40)))
    }

    @Test
    fun sameOrNewerResolutionIsApplied() {
        assertFalse(shouldKeepCachedMetadata(cachedKind41, "owner", event("u41", 200, 41)))
        assertFalse(shouldKeepCachedMetadata(cachedKind41, "owner", event("u42", 300, 41)))
    }

    @Test
    fun sameSecondTieFollowsResolverRuleOfSmallerEventId() {
        // resolver は同時刻なら小さい ID を選ぶ。保存値の方が ID が大きければ解決結果を採用する。
        assertFalse(shouldKeepCachedMetadata(cachedKind41, "owner", event("u40", 200, 41)))
        assertTrue(shouldKeepCachedMetadata(cachedKind41, "owner", event("u42", 200, 41)))
    }

    @Test
    fun cacheWithDifferentOwnerOrMissingCacheIsIgnored() {
        assertFalse(shouldKeepCachedMetadata(cachedKind41, "someone-else", event("c40", 100, 40)))
        assertFalse(shouldKeepCachedMetadata(null, "owner", event("c40", 100, 40)))
    }

    private fun event(id: String, createdAt: Long, kind: Int) =
        NostrEvent(id, "owner", createdAt, kind, emptyList(), "{}", "sig")
}
