package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileResendTest {
    private val me = "a".repeat(64)

    private fun profile(createdAt: Long, id: String = createdAt.toString().padStart(64, '0'), pubkey: String = me, kind: Int = 0) =
        NostrEvent(id = id, pubkey = pubkey, createdAt = createdAt, kind = kind, tags = emptyList(), content = "{}", sig = "")

    @Test
    fun resendsWhenRelayHasNone() {
        val local = profile(100)
        assertEquals(local, profileEventToResend(local, RelayProfileCheck.Answered(null)))
    }

    @Test
    fun resendsWhenRelayIsOlder() {
        val local = profile(200)
        assertEquals(local, profileEventToResend(local, RelayProfileCheck.Answered(profile(100))))
    }

    @Test
    fun doesNotResendWhenRelayIsNewerOrSame() {
        val local = profile(100)
        assertNull(profileEventToResend(local, RelayProfileCheck.Answered(profile(200))))
        assertNull(profileEventToResend(local, RelayProfileCheck.Answered(local)))
    }

    @Test
    fun sameCreatedAtUsesLowerIdAsNewer() {
        val lower = profile(100, id = "1".repeat(64))
        val higher = profile(100, id = "2".repeat(64))
        assertEquals(lower, profileEventToResend(lower, RelayProfileCheck.Answered(higher)))
        assertNull(profileEventToResend(higher, RelayProfileCheck.Answered(lower)))
    }

    @Test
    fun doesNotResendWithoutAnswerOrLocalEvent() {
        assertNull(profileEventToResend(profile(100), RelayProfileCheck.Unanswered))
        assertNull(profileEventToResend(null, RelayProfileCheck.Answered(null)))
    }

    @Test
    fun ignoresMismatchedEvents() {
        assertNull(profileEventToResend(profile(100, kind = 1), RelayProfileCheck.Answered(null)))
        assertNull(profileEventToResend(profile(200), RelayProfileCheck.Answered(profile(100, pubkey = "b".repeat(64)))))
    }
}
