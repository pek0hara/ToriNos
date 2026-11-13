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

    @Test
    fun pinnedProfilesAreNotEvicted() {
        ProfileCache.setPinnedPubkeys(setOf("bounded-profile-0"))
        repeat(2_001) { index ->
            ProfileCache.putEvent(
                profileEvent("bounded-profile-$index", createdAt = index.toLong(), picture = "$index"),
                fetchedAt = index.toLong(),
            )
        }

        assertEquals(2_000, ProfileCache.snapshotEntries().size)
        assertEquals("0", ProfileCache.get("bounded-profile-0")?.picture)
        assertNull(ProfileCache.get("bounded-profile-1"))
    }

    @Test
    fun optimisticEditDropsSourceEventOfPreviousProfile() {
        val event = profileEvent("profile-cache-optimistic-event", createdAt = 10, picture = "old")
        ProfileCache.putEvent(event)

        ProfileCache.putOptimistic(event.pubkey, NostrProfile(picture = "edited"))

        assertNull(ProfileCache.getEntry(event.pubkey)?.event)
    }

    @Test
    fun optimisticUpdateWithSameProfileKeepsEntryForPersistence() {
        // プロフィール画面は表示中のプロフィールをそのまま渡す。編集ではないので保存対象から外さない。
        val event = profileEvent("profile-cache-same-optimistic", createdAt = 10, picture = "same")
        ProfileCache.putEvent(event, fetchedAt = 1_000)
        val version = ProfileCache.persistVersion.value

        ProfileCache.putOptimistic(event.pubkey, ProfileCache.get(event.pubkey)!!)

        assertEquals(event, ProfileCache.getEntry(event.pubkey)?.event)
        assertEquals(1_000, ProfileCache.getEntry(event.pubkey)?.fetchedAt)
        assertEquals(version, ProfileCache.persistVersion.value)
    }

    @Test
    fun entryKeepsSourceEventForPersistence() {
        val event = profileEvent("profile-cache-event-test", createdAt = 10, picture = "kept")
        ProfileCache.putEvent(event, fetchedAt = 1_000)

        assertEquals(event, ProfileCache.getEntry(event.pubkey)?.event)
    }

    @Test
    fun restore_addsMissingProfilesWithStoredFetchedAt() {
        val event = profileEvent("profile-cache-restore-test", createdAt = 10, picture = "restored")

        ProfileCache.restore(listOf(event to 1_234L))

        assertEquals("restored", ProfileCache.get(event.pubkey)?.picture)
        assertEquals(1_234, ProfileCache.getEntry(event.pubkey)?.fetchedAt)
    }

    @Test
    fun restore_doesNotReplaceNewerOrOptimisticProfiles() {
        val newer = profileEvent("restore-newer", createdAt = 20, picture = "relay")
        ProfileCache.putEvent(newer, fetchedAt = 5_000)
        ProfileCache.putOptimistic("restore-optimistic", NostrProfile(picture = "edited"))

        ProfileCache.restore(
            listOf(
                profileEvent("restore-newer", createdAt = 10, picture = "stored") to 9_000L,
                profileEvent("restore-optimistic", createdAt = 10, picture = "stored") to 9_000L,
            ),
        )

        assertEquals("relay", ProfileCache.get("restore-newer")?.picture)
        assertEquals(5_000, ProfileCache.getEntry("restore-newer")?.fetchedAt)
        assertEquals("edited", ProfileCache.get("restore-optimistic")?.picture)
    }

    @Test
    fun restore_replacesOlderProfileButKeepsNewerFetchedAt() {
        ProfileCache.putEvent(profileEvent("restore-older", createdAt = 10, picture = "relay"), fetchedAt = 5_000)

        ProfileCache.restore(listOf(profileEvent("restore-older", createdAt = 20, picture = "stored") to 1_000L))

        assertEquals("stored", ProfileCache.get("restore-older")?.picture)
        assertEquals(5_000, ProfileCache.getEntry("restore-older")?.fetchedAt)
    }

    @Test
    fun newerEventWithWrongTypedLud16_replacesOlderLightningAddress() {
        val pubkey = "profile-cache-broken-lud16-test"
        ProfileCache.putEvent(rawProfileEvent(pubkey, createdAt = 100, content = """{"name":"old","lud16":"old@a.example"}"""))
        ProfileCache.putEvent(rawProfileEvent(pubkey, createdAt = 200, content = """{"name":"new","lud16":["a@a.example","b@b.example"]}"""))

        val profile = ProfileCache.get(pubkey)
        assertEquals("new", profile?.name)
        assertNull(profile?.lud16)
        assertEquals("$pubkey-200", ProfileCache.getEntry(pubkey)?.eventId)
    }

    private fun rawProfileEvent(pubkey: String, createdAt: Long, content: String) = NostrEvent(
        id = "$pubkey-$createdAt",
        pubkey = pubkey,
        createdAt = createdAt,
        kind = 0,
        tags = emptyList(),
        content = content,
        sig = "",
    )

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
