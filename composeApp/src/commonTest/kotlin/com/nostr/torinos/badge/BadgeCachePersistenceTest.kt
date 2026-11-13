package com.nostr.torinos.badge

import com.nostr.torinos.network.LocalTextStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BadgeCachePersistenceTest {
    private val data = listOf(badgeEvent(10008, tags = badgePair().tags), badgeAward(), badgeDefinition())
    private val delay = com.nostr.torinos.network.CachePersistence.WRITE_DELAY_MS

    @Test fun restoresStoredBadgesAndBecomesReady() = runTest {
        val storage = MemoryTextStore(BadgeEventStore().apply { put(data) }.encode(maxUsers = 10))
        val store = BadgeEventStore(ready = false)

        badgeCachePersistence(store, storage, backgroundScope, emptyFlow()).start()
        assertFalse(store.ready.value)
        runCurrent()

        assertTrue(store.ready.value)
        assertEquals(data.toSet(), store.eventsFor(badgeOwner).toSet())
    }

    @Test fun restoringAloneDoesNotRewriteStoreButEarlierChangesAreWritten() = runTest {
        val storage = MemoryTextStore(BadgeEventStore().apply { put(data) }.encode(maxUsers = 10))
        val store = BadgeEventStore(ready = false)
        badgeCachePersistence(store, storage, backgroundScope, emptyFlow()).start()
        runCurrent()
        advanceTimeBy(delay * 2); runCurrent()
        assertEquals(0, storage.writes)

        // A change made before the writer subscribes must still be written.
        val late = BadgeEventStore(ready = false)
        val lateStorage = MemoryTextStore()
        val persistence = badgeCachePersistence(late, lateStorage, backgroundScope, emptyFlow())
        persistence.start()
        late.put(data)
        advanceTimeBy(delay); runCurrent()
        assertEquals(1, lateStorage.writes)
    }

    @Test fun storeWithoutStorageIsReadyImmediately() = runTest {
        val store = BadgeEventStore(ready = false)
        badgeCachePersistence(store, storage = null, backgroundScope, emptyFlow()).start()

        assertTrue(store.ready.value)
    }

    @Test fun changesAreWrittenOnceAfterDelay() = runTest {
        val storage = MemoryTextStore()
        val store = BadgeEventStore(ready = false)
        badgeCachePersistence(store, storage, backgroundScope, emptyFlow()).start()
        runCurrent()

        store.put(data.take(1))
        advanceTimeBy(1_000); runCurrent()
        store.put(data.drop(1))
        advanceTimeBy(delay - 1_000 - 1); runCurrent()
        assertEquals(0, storage.writes)

        advanceTimeBy(1); runCurrent()
        assertEquals(1, storage.writes)
        val restored = BadgeEventStore().apply { restore(storage.content!!) }
        assertEquals(data.toSet(), restored.eventsFor(badgeOwner).toSet())
    }

    @Test fun savedAccountsArePinned() = runTest {
        var now = 0L
        val store = BadgeEventStore(maxUsers = 1, nowMillis = { now })
        badgeCachePersistence(store, storage = null, backgroundScope, MutableStateFlow(setOf(badgeOwner))).start()
        runCurrent()

        store.put(data)
        now = 1; store.put(listOf(badgeEvent(10008, pubkey = "1".repeat(64), id = "1".repeat(64))))

        assertEquals(3, store.eventsFor(badgeOwner).size)
    }

    private class MemoryTextStore(var content: String? = null) : LocalTextStore {
        var writes = 0
        override suspend fun read(): String? = content
        override suspend fun write(content: String) {
            writes++
            this.content = content
        }
    }
}
