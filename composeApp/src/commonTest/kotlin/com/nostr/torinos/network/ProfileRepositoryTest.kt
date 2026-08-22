package com.nostr.torinos.network

import com.nostr.torinos.model.NostrProfile
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
