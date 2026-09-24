package com.nostr.torinos.network

import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChannelLocalStoreTest {
    private class MemoryStorage(var value: String? = null) : ChannelLocalStorage {
        var writes = 0
        var failWrites = false
        override suspend fun read(): String? = value
        override suspend fun write(value: String) {
            if (failWrites) throw IllegalStateException("disk full")
            writes++
            this.value = value
        }
    }

    private fun store(storage: ChannelLocalStorage, scope: CoroutineScope, maxStates: Int = 1_000) =
        ChannelLocalStateStore(storage, scope, persistDebounceMs = 400, maxStates = maxStates)

    @Test
    fun stateSurvivesRestartThroughJson() = runTest {
        val storage = MemoryStorage()
        val first = store(storage, backgroundScope)
        first.recordChannelCreate(create("c1", 10, "one"), ChannelMeta(name = "one"), "wss://yabu.me/")
        first.upsertChannelMetadata(
            create("c1", 10, "one"),
            update("u1", 20),
            ChannelMeta(name = "renamed", relays = listOf("wss://A.example/", "https://bad", "wss://a.example")),
        )
        first.markRead("c1", 30)
        first.saveReadingPosition("c1", ChannelReadingPosition("m1", 29, 12))
        first.setFavorite("c1", true)

        val restored = store(storage, backgroundScope).get("c1")
        assertNotNull(restored)
        assertEquals("renamed", restored.meta.name)
        assertEquals(listOf("wss://a.example"), restored.meta.relays)
        assertEquals(41, restored.metadataKind)
        assertEquals("u1", restored.metadataEventId)
        assertEquals(listOf("wss://yabu.me"), restored.observedRelays)
        assertEquals(30, restored.lastReadAt)
        assertEquals(ChannelReadingPosition("m1", 29, 12), restored.readingPosition)
        assertTrue(restored.isFavorite)
    }

    @Test
    fun kind40RefetchDoesNotOverwriteKind41Metadata() = runTest {
        val store = store(MemoryStorage(), backgroundScope)
        store.upsertChannelMetadata(create("c1", 10, "old"), update("u1", 20), ChannelMeta(name = "new"))
        store.recordChannelCreate(create("c1", 10, "old"), ChannelMeta(name = "old"), "wss://r1")
        val state = store.get("c1")!!
        assertEquals("new", state.meta.name)
        assertEquals("u1", state.metadataEventId)
        assertEquals(listOf("wss://r1"), state.observedRelays)
    }

    @Test
    fun olderResolutionDoesNotReplaceNewerStoredMetadata() = runTest {
        val store = store(MemoryStorage(), backgroundScope)
        store.upsertChannelMetadata(create("c1", 10, "a"), update("u2", 30), ChannelMeta(name = "latest"))
        // 別リレーで開き、古い kind 41 しか届かなかった場合でも保存済みの新しい方を維持する。
        store.upsertChannelMetadata(create("c1", 10, "a"), update("u1", 20), ChannelMeta(name = "stale"))
        store.upsertChannelMetadata(create("c1", 10, "a"), create("c1", 10, "a"), ChannelMeta(name = "kind40"))
        assertEquals("latest", store.get("c1")!!.meta.name)
        // 同時刻は resolver と同じく event ID が小さい方を採用する。
        store.upsertChannelMetadata(create("c1", 10, "a"), update("u3", 30), ChannelMeta(name = "tie-larger-id"))
        assertEquals("latest", store.get("c1")!!.meta.name)
        store.upsertChannelMetadata(create("c1", 10, "a"), update("u1", 30), ChannelMeta(name = "tie-smaller-id"))
        assertEquals("tie-smaller-id", store.get("c1")!!.meta.name)
    }

    @Test
    fun observeFiltersByObservedRelayAndHidesStubs() = runTest {
        val store = store(MemoryStorage(), backgroundScope)
        store.recordChannelCreate(create("c1", 10, "one"), ChannelMeta(name = "one"), "wss://r1/")
        store.recordChannelCreate(create("c2", 11, "two"), ChannelMeta(name = "two"), "wss://r2")
        store.markRead("stub", 5) // kind 40 未取得の既読行は一覧に出さない
        assertEquals(listOf("c1"), store.observe("wss://R1").first().map { it.channelId })
        assertEquals(listOf("c2"), store.observe("wss://r2/").first().map { it.channelId })
    }

    @Test
    fun latestMessageKeepsOnlyNewestAndIsPersistedWithDebounce() = runTest {
        val storage = MemoryStorage()
        val store = store(storage, backgroundScope)
        store.recordChannelCreate(create("c1", 10, "one"), ChannelMeta(name = "one"), "wss://r1")
        val writesBefore = storage.writes
        store.recordLatestMessage("c1", message("m2", 20, "x".repeat(500)))
        store.recordLatestMessage("c1", message("m1", 15, "older"))
        store.recordLatestMessage("c1", message("m3", 20, "tie-larger-id"))
        assertEquals("m2", store.get("c1")!!.latestMessage!!.eventId)
        store.recordLatestMessage("c1", message("m1b", 20, "tie-smaller-id"))
        assertEquals("m1b", store.get("c1")!!.latestMessage!!.eventId)
        assertEquals(writesBefore, storage.writes)
        advanceTimeBy(401)
        runCurrent()
        assertEquals(writesBefore + 1, storage.writes)
        val restored = store(storage, backgroundScope).get("c1")!!.latestMessage!!
        assertEquals("m1b", restored.eventId)

        store.recordLatestMessage("c1", message("m4", 40, "y".repeat(500)))
        assertEquals(ChannelLocalStateStore.PREVIEW_MAX_CHARS, store.get("c1")!!.latestMessage!!.contentPreview.length)
    }

    @Test
    fun clearingDeletedLatestMessageOnlyAffectsThatEvent() = runTest {
        val store = store(MemoryStorage(), backgroundScope)
        store.recordChannelCreate(create("c1", 10, "one"), ChannelMeta(name = "one"), "wss://r1")
        store.recordLatestMessage("c1", message("m1", 20, "hi"))
        store.clearLatestMessage("c1", "other")
        assertEquals("m1", store.get("c1")!!.latestMessage?.eventId)
        store.clearLatestMessage("c1", "m1")
        assertNull(store.get("c1")!!.latestMessage)
    }

    @Test
    fun latestMessageForUnknownChannelIsIgnored() = runTest {
        val store = store(MemoryStorage(), backgroundScope)
        store.recordLatestMessage("unknown", message("m1", 1, "hi"))
        assertNull(store.get("unknown"))
    }

    @Test
    fun markReadNeverMovesBackwards() = runTest {
        val store = store(MemoryStorage(), backgroundScope)
        store.markRead("c1", 50)
        store.markRead("c1", 40)
        assertEquals(50, store.get("c1")!!.lastReadAt)
    }

    @Test
    fun overflowEvictsUnopenedNonFavoritesFirst() = runTest {
        val store = store(MemoryStorage(), backgroundScope, maxStates = 2)
        store.recordChannelCreate(create("fav", 1, "f"), ChannelMeta(name = "f"), "wss://r1")
        store.setFavorite("fav", true)
        store.recordChannelCreate(create("opened", 2, "o"), ChannelMeta(name = "o"), "wss://r1")
        store.markRead("opened", 5)
        store.recordChannelCreate(create("fresh", 3, "n"), ChannelMeta(name = "n"), "wss://r1")
        assertNotNull(store.get("fav"))
        assertNotNull(store.get("opened"))
        assertNull(store.get("fresh"))
    }

    @Test
    fun corruptedOrFailingStorageDoesNotBreakInMemoryState() = runTest {
        val storage = MemoryStorage("{not json")
        val store = store(storage, backgroundScope)
        assertNull(store.get("c1"))
        storage.failWrites = true
        store.markRead("c1", 1)
        assertEquals(1, store.get("c1")!!.lastReadAt)
    }

    @Test
    fun unknownFieldsFromFutureVersionsAreIgnored() = runTest {
        val storage = MemoryStorage(
            """{"c1":{"channelId":"c1","ownerPubkey":"p","metadataEventId":"c1","futureField":1}}""",
        )
        assertEquals("p", store(storage, backgroundScope).get("c1")!!.ownerPubkey)
    }

    private fun create(id: String, createdAt: Long, name: String) =
        NostrEvent(id, "owner", createdAt, 40, emptyList(), """{"name":"$name"}""", "sig")

    private fun update(id: String, createdAt: Long) =
        NostrEvent(id, "owner", createdAt, 41, listOf(listOf("e", "c1", "", "root")), "{}", "sig")

    private fun message(id: String, createdAt: Long, content: String) =
        NostrEvent(id, "author", createdAt, 42, listOf(listOf("e", "c1", "", "root")), content, "sig")
}
