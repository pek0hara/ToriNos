package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProfileCacheTest {

    @AfterTest
    fun tearDown() {
        ProfileCache.clearForTest()
    }

    @Test
    fun putEvent_returnsNewestCachedProfile_whenOlderEventArrivesLater() {
        val pubkey = "profile-cache-newest-test"
        val newer = profileEvent(
            pubkey = pubkey,
            createdAt = 200,
            picture = "https://example.com/new.jpg",
        )
        val older = profileEvent(
            pubkey = pubkey,
            createdAt = 100,
            picture = "https://example.com/old.jpg",
        )

        assertEquals("https://example.com/new.jpg", ProfileCache.putEvent(newer)?.picture)
        assertEquals("https://example.com/new.jpg", ProfileCache.putEvent(older)?.picture)
        assertEquals("https://example.com/new.jpg", ProfileCache.get(pubkey)?.picture)
    }

    @Test
    fun putEvent_usesEventIdAsTieBreaker_whenCreatedAtIsEqual() {
        val pubkey = "profile-cache-tie-break-test"
        val higherId = profileEvent(pubkey, createdAt = 300, picture = "higher", id = "ff")
        val lowerId = profileEvent(pubkey, createdAt = 300, picture = "lower", id = "00")

        ProfileCache.putEvent(higherId)
        assertEquals("lower", ProfileCache.putEvent(lowerId)?.picture)
        assertEquals("00", ProfileCache.getEntry(pubkey)?.eventId)
    }

    @Test
    fun putOptimistic_keepsEventVersionUntilNewerEventArrives() {
        val pubkey = "profile-cache-optimistic-test"
        ProfileCache.putEvent(profileEvent(pubkey, createdAt = 400, picture = "published", id = "01"))

        ProfileCache.putOptimistic(pubkey, com.nostr.torinos.model.NostrProfile(picture = "edited"))
        assertEquals("edited", ProfileCache.get(pubkey)?.picture)
        assertEquals("01", ProfileCache.getEntry(pubkey)?.eventId)
        assertEquals(400, ProfileCache.getEntry(pubkey)?.createdAt)

        ProfileCache.putEvent(profileEvent(pubkey, createdAt = 399, picture = "older", id = "00"))
        assertEquals("edited", ProfileCache.get(pubkey)?.picture)

        ProfileCache.putEvent(profileEvent(pubkey, createdAt = 401, picture = "newer", id = "02"))
        assertEquals("newer", ProfileCache.get(pubkey)?.picture)
    }

    @Test
    fun putEvent_rejectsNonProfileKind() {
        val event = profileEvent(
            pubkey = "profile-cache-kind-test",
            createdAt = 500,
            picture = "invalid",
            kind = 1,
        )

        assertFailsWith<IllegalArgumentException> { ProfileCache.putEvent(event) }
    }

    @Test
    fun putEvent_updatesFetchedAt_withoutReplacingNewerProfile() {
        val pubkey = "profile-cache-fetched-at-test"
        val newer = profileEvent(pubkey, createdAt = 700, picture = "newer", id = "01")
        val older = profileEvent(pubkey, createdAt = 600, picture = "older", id = "00")

        ProfileCache.putEvent(newer, fetchedAt = 1_000)
        ProfileCache.putEvent(older, fetchedAt = 2_000)

        assertEquals("newer", ProfileCache.get(pubkey)?.picture)
        assertEquals(2_000, ProfileCache.getEntry(pubkey)?.fetchedAt)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun putEvents_publishesOneUpdateForOneHundredProfiles() = runTest {
        val pubkeys = (0 until 100).mapTo(linkedSetOf()) { "batch-profile-$it" }
        val emissions = mutableListOf<Map<String, NostrProfile>>()
        val observer = launch(UnconfinedTestDispatcher(testScheduler)) {
            ProfileCache.observe(pubkeys).take(2).toList(emissions)
        }

        ProfileCache.putEvents(
            pubkeys.mapIndexed { index, pubkey ->
                profileEvent(pubkey, createdAt = index.toLong(), picture = "picture-$index")
            },
            fetchedAt = 1_000,
        )
        observer.join()

        assertEquals(2, emissions.size)
        assertEquals(emptyMap(), emissions.first())
        assertEquals(100, emissions.last().size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun targetedObservationIgnoresUnrelatedProfileUpdates() = runTest {
        val emissions = mutableListOf<NostrProfile?>()
        val observer = launch(UnconfinedTestDispatcher(testScheduler)) {
            ProfileCache.observe("target-profile").take(2).toList(emissions)
        }

        ProfileCache.putEvent(profileEvent("unrelated-profile", 1, "unrelated"))
        runCurrent()
        assertEquals(listOf<NostrProfile?>(null), emissions)

        ProfileCache.putEvent(profileEvent("target-profile", 2, "target"))
        observer.join()
        assertEquals(listOf(null, ProfileCache.get("target-profile")), emissions)
    }

    @Test
    fun cacheEvictsOldestFetchedProfilesWithoutExceedingLimit() {
        repeat(2_001) { index ->
            ProfileCache.putEvent(
                profileEvent("bounded-profile-$index", createdAt = index.toLong(), picture = "$index"),
                fetchedAt = index.toLong(),
            )
        }

        assertEquals(2_000, ProfileCache.snapshotEntries().size)
        assertNull(ProfileCache.get("bounded-profile-0"))
        assertEquals("2000", ProfileCache.get("bounded-profile-2000")?.picture)
    }

    private fun profileEvent(
        pubkey: String,
        createdAt: Long,
        picture: String,
        id: String = "$pubkey-$createdAt",
        kind: Int = 0,
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = emptyList(),
        content = """{"picture":"$picture"}""",
        sig = "",
    )
}
