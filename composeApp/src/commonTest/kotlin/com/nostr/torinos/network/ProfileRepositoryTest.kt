package com.nostr.torinos.network

import com.nostr.torinos.model.NostrProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileRepositoryTest {
    private val freshEntry = ProfileCache.Entry(
        profile = NostrProfile(name = "fresh"),
        eventId = "01",
        createdAt = 100,
        fetchedAt = 9_000,
    )
    private val staleEntry = ProfileCache.Entry(
        profile = NostrProfile(name = "stale"),
        eventId = "02",
        createdAt = 90,
        fetchedAt = 1_000,
    )

    @Test
    fun cacheOnly_neverFetches() {
        val result = selectPubkeysToFetch(
            pubkeys = setOf("fresh", "missing"),
            entries = mapOf("fresh" to freshEntry),
            policy = ProfileFetchPolicy.CacheOnly,
            now = 10_000,
        )

        assertEquals(emptySet(), result)
    }

    @Test
    fun cacheFirst_fetchesOnlyMissingAndStaleProfiles() {
        val result = selectPubkeysToFetch(
            pubkeys = setOf("fresh", "stale", "missing"),
            entries = mapOf("fresh" to freshEntry, "stale" to staleEntry),
            policy = ProfileFetchPolicy.CacheFirst(maxAgeMillis = 5_000),
            now = 10_000,
        )

        assertEquals(setOf("stale", "missing"), result)
    }

    @Test
    fun forceRefresh_fetchesAllProfiles() {
        val requested = setOf("fresh", "stale", "missing")
        val result = selectPubkeysToFetch(
            pubkeys = requested,
            entries = mapOf("fresh" to freshEntry, "stale" to staleEntry),
            policy = ProfileFetchPolicy.ForceRefresh,
            now = 10_000,
        )

        assertEquals(requested, result)
    }

    @Test
    fun missingProfilesOnHintedRelayFallBackToAllRelays() {
        val result = profileFallbackPubkeys(
            requestedPubkeys = setOf("found", "missing"),
            receivedPubkeys = setOf("found"),
            relayHint = "wss://example.com",
        )

        assertEquals(setOf("missing"), result)
    }

    @Test
    fun allRelayFetchDoesNotScheduleAnotherFallback() {
        val result = profileFallbackPubkeys(
            requestedPubkeys = setOf("missing"),
            receivedPubkeys = emptySet(),
            relayHint = null,
        )

        assertEquals(emptySet(), result)
    }

    @Test
    fun missingProfilesOnPrimaryRelaysFallBackToAllRelays() {
        val result = profileFallbackPubkeys(
            requestedPubkeys = setOf("found", "missing"),
            receivedPubkeys = setOf("found"),
            relayHint = null,
            progressive = true,
        )

        assertEquals(setOf("missing"), result)
    }

    @Test
    fun primaryRelaysAreTheFirstConfiguredRelays() {
        val result = profilePrimaryRelays(
            allRelays = listOf("wss://a", "wss://b", "wss://c", "wss://d"),
            count = 2,
        )

        assertEquals(listOf("wss://a", "wss://b"), result?.toList())
    }

    @Test
    fun noPrimaryRelaysWhenAllRelaysFitWithinTheLimit() {
        assertEquals(null, profilePrimaryRelays(listOf("wss://a", "wss://b"), count = 2))
        assertEquals(null, profilePrimaryRelays(emptyList(), count = 2))
    }

    @Test
    fun progressiveFetchIsUsedOnlyForTheFirstFetchOfUncachedProfiles() {
        assertTrue(useProgressiveProfileFetch(relayHint = null, escalated = false, anyCached = false))
    }

    @Test
    fun refetchingCachedProfilesAsksAllRelaysBecauseAPrimaryRelayMayHoldAnOlderVersion() {
        assertFalse(useProgressiveProfileFetch(relayHint = null, escalated = false, anyCached = true))
    }

    @Test
    fun progressiveFetchIsNotUsedWithARelayHintOrAfterEscalation() {
        assertFalse(useProgressiveProfileFetch(relayHint = "wss://example.com", escalated = false, anyCached = false))
        assertFalse(useProgressiveProfileFetch(relayHint = null, escalated = true, anyCached = false))
    }

    @Test
    fun extraProfileRelaysAreAskedOnlyWhenNamed() {
        val buzz = setOf("wss://buzz.example")
        // 全リレーへの取得には加えない(フィードで見かけた人の問い合わせを、認証済みのコミュニティへ送らない)。
        assertEquals(
            RelayTarget.AllEnabled,
            profileFetchTarget(relayHint = null, primaryRelays = null, extraRelays = buzz),
        )
        // 先頭の少数リレーへの段階取得はそのまま。
        assertEquals(
            RelayTarget.Explicit(setOf("wss://a.example")),
            profileFetchTarget(relayHint = null, primaryRelays = setOf("wss://a.example"), extraRelays = buzz),
        )
        // Single は設定済みのリレーにしか届かない。Buzz のリレーの指定は、そのリレーへ確実に聞く。
        assertEquals(
            RelayTarget.Explicit(setOf("wss://buzz.example")),
            profileFetchTarget(relayHint = "wss://buzz.example", primaryRelays = null, extraRelays = buzz),
        )
        // それ以外のリレー指定は従来どおり(設定済みのリレーだけ)。
        assertEquals(
            RelayTarget.Single("wss://other.example"),
            profileFetchTarget(relayHint = "wss://other.example", primaryRelays = null, extraRelays = buzz),
        )
    }

    @Test
    fun extraProfileRelaysFollowTheCurrentOwner() {
        val owner = Any()
        ProfileExtraRelays.set(owner, setOf("wss://buzz.example"))
        assertEquals(setOf("wss://buzz.example"), ProfileExtraRelays.current())
        // 別のアカウントの後片付けで消さない。
        ProfileExtraRelays.clear(Any())
        assertEquals(setOf("wss://buzz.example"), ProfileExtraRelays.current())
        ProfileExtraRelays.clear(owner)
        assertEquals(emptySet(), ProfileExtraRelays.current())
    }
}
