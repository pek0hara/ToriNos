package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePersistenceTest {

    @AfterTest
    fun tearDown() {
        ProfileCache.clearForTest()
    }

    @Test
    fun startRestoresStoredProfilesIntoCache() = runTest {
        val event = profileEvent("persisted-restore", createdAt = 10, name = "保存済み")
        val storage = MemoryTextStore(encodeProfileStore(listOf(event to 1_234L)))

        persistence(storage).start()
        runCurrent()

        assertEquals("保存済み", ProfileCache.get(event.pubkey)?.name)
        assertEquals(1_234, ProfileCache.getEntry(event.pubkey)?.fetchedAt)
    }

    @Test
    fun restoringAloneDoesNotRewriteStore() = runTest {
        val event = profileEvent("persisted-no-rewrite", createdAt = 10, name = "保存済み")
        val storage = MemoryTextStore(encodeProfileStore(listOf(event to 1_234L)))

        persistence(storage).start()
        runCurrent()
        advanceTimeBy(WriteDelay * 2)
        runCurrent()

        assertEquals(0, storage.writes)
    }

    @Test
    fun changesAreWrittenOnceAfterDelay() = runTest {
        val storage = MemoryTextStore()
        persistence(storage).start()
        runCurrent()
        val writesBefore = storage.writes

        ProfileCache.putEvent(profileEvent("persisted-a", createdAt = 1, name = "a"), fetchedAt = 100)
        runCurrent()
        advanceTimeBy(1_000)
        ProfileCache.putEvent(profileEvent("persisted-b", createdAt = 1, name = "b"), fetchedAt = 200)
        runCurrent()
        advanceTimeBy(WriteDelay - 1_000 - 1)
        runCurrent()
        assertEquals(writesBefore, storage.writes)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(writesBefore + 1, storage.writes)
        val stored = decodeProfileStore(storage.content!!).associateBy { it.first.pubkey }
        assertEquals(100L, stored.getValue("persisted-a").second)
        assertEquals("""{"name":"b"}""", stored.getValue("persisted-b").first.content)
    }

    @Test
    fun optimisticProfilesWithoutEventAreNotWritten() = runTest {
        val storage = MemoryTextStore()
        persistence(storage).start()
        runCurrent()

        ProfileCache.putOptimistic("persisted-optimistic", NostrProfile(name = "編集中"))
        runCurrent()
        advanceTimeBy(WriteDelay)
        runCurrent()

        val stored = storage.content?.let(::decodeProfileStore).orEmpty()
        assertTrue(stored.none { it.first.pubkey == "persisted-optimistic" })
    }

    @Test
    fun writtenProfilesAreLimitedAndKeepPinnedAccountsFirst() = runTest {
        val storage = MemoryTextStore()
        val accounts = MutableStateFlow(setOf("persisted-pinned"))
        persistence(storage, accounts).start()
        runCurrent()

        ProfileCache.putEvent(profileEvent("persisted-pinned", createdAt = 1, name = "me"), fetchedAt = 0)
        ProfileCache.putEvents(
            (1..MaxPersisted + 10).map { profileEvent("persisted-$it", createdAt = 1, name = "$it") },
            fetchedAt = 1_000,
        )
        (1..MaxPersisted + 10).forEach {
            ProfileCache.markFetched(listOf("persisted-$it"), fetchedAt = 1_000L + it)
        }
        runCurrent()
        advanceTimeBy(WriteDelay)
        runCurrent()

        val stored = decodeProfileStore(storage.content!!).map { it.first.pubkey }
        assertEquals(MaxPersisted, stored.size)
        assertTrue("persisted-pinned" in stored)
        assertTrue("persisted-${MaxPersisted + 10}" in stored)
        assertTrue("persisted-1" !in stored)
    }

    @Test
    fun accountPubkeysArePinnedInCache() = runTest {
        val accounts = MutableStateFlow(setOf("pinned-a"))
        persistence(storage = null, accounts = accounts).start()
        runCurrent()
        assertEquals(setOf("pinned-a"), ProfileCache.pinnedPubkeys())

        accounts.value = setOf("pinned-a", "pinned-b")
        runCurrent()
        assertEquals(setOf("pinned-a", "pinned-b"), ProfileCache.pinnedPubkeys())
    }

    @Test
    fun brokenStoreIsIgnoredAndOverwritten() = runTest {
        val storage = MemoryTextStore("not json")
        persistence(storage).start()
        runCurrent()

        ProfileCache.putEvent(profileEvent("persisted-after-broken", createdAt = 1, name = "ok"))
        runCurrent()
        advanceTimeBy(WriteDelay)
        runCurrent()

        assertEquals(listOf("persisted-after-broken"), decodeProfileStore(storage.content!!).map { it.first.pubkey })
    }

    @Test
    fun decodeSkipsNonProfileEventsAndUnknownVersion() {
        val profile = profileEvent("decode-profile", createdAt = 1, name = "ok")
        val note = profile.copy(id = "note", kind = 1)

        assertEquals(listOf("decode-profile"), decodeProfileStore(encodeProfileStore(listOf(profile to 1L, note to 1L))).map { it.first.pubkey })
        assertNull(decodeProfileStore("""{"version":999,"entries":[]}""").firstOrNull())
    }

    private fun TestScope.persistence(
        storage: LocalTextStore?,
        accounts: Flow<Set<String>> = emptyFlow(),
    ) = profileCachePersistence(
        storage = storage,
        scope = backgroundScope,
        accountPubkeys = accounts,
    )

    private class MemoryTextStore(var content: String? = null) : LocalTextStore {
        var writes = 0
        override suspend fun read(): String? = content
        override suspend fun write(content: String) {
            writes++
            this.content = content
        }
    }

    private fun profileEvent(pubkey: String, createdAt: Long, name: String) = NostrEvent(
        id = "$pubkey-$createdAt",
        pubkey = pubkey,
        createdAt = createdAt,
        kind = 0,
        tags = emptyList(),
        content = """{"name":"$name"}""",
        sig = "",
    )

    private companion object {
        const val WriteDelay = CachePersistence.WRITE_DELAY_MS
        const val MaxPersisted = MAX_PERSISTED_PROFILES
    }
}
