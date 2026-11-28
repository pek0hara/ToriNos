package com.nostr.torinos.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatDraftStoreTest {
    private class MemoryStorage : ChatDraftStorage {
        val rows = mutableMapOf<String, String>()
        var writes = 0
        override suspend fun read(key: String) = rows[key]
        override suspend fun write(key: String, value: String?) {
            writes++
            if (value == null) rows.remove(key) else rows[key] = value
        }
    }

    private fun TestScope.store(storage: ChatDraftStorage, owner: String = "me", scope: CoroutineScope = backgroundScope, now: () -> Long = { 1_000L }) =
        ChatDraftStore(owner, scope, storage, now = now, saveDelayMillis = 500)

    @Test
    fun savesAfterTypingPausesAndRestoresInNewStore() = runTest {
        val storage = MemoryStorage()
        val first = store(storage)
        first.update("room", "@Alice こんにちは", listOf(ChatDraftMention("pk", "Alice")))
        assertEquals("@Alice こんにちは", first.draft("room")?.text)
        runCurrent()
        assertEquals(0, storage.writes)
        advanceTimeBy(501)
        runCurrent()
        assertEquals(1, storage.writes)

        val second = store(storage)
        val restored = second.awaitDraft("room")
        assertEquals("@Alice こんにちは", restored?.text)
        assertEquals(listOf(ChatDraftMention("pk", "Alice")), restored?.mentions)
    }

    @Test
    fun keystrokesWithinDelayAreWrittenOnce() = runTest {
        val storage = MemoryStorage()
        val drafts = store(storage)
        drafts.update("room", "a")
        advanceTimeBy(200)
        drafts.update("room", "ab")
        advanceTimeBy(200)
        drafts.update("room", "abc")
        advanceTimeBy(501)
        runCurrent()
        assertEquals(1, storage.writes)
        assertEquals("abc", store(storage).awaitDraft("room")?.text)
    }

    @Test
    fun flushWritesImmediately() = runTest {
        val storage = MemoryStorage()
        val drafts = store(storage)
        drafts.update("room", "途中")
        drafts.flush()
        runCurrent()
        assertEquals("途中", store(storage).awaitDraft("room")?.text)
    }

    @Test
    fun blankTextRemovesDraft() = runTest {
        val storage = MemoryStorage()
        val drafts = store(storage)
        drafts.update("room", "消す")
        drafts.flush(); runCurrent()
        drafts.update("room", "  ")
        assertNull(drafts.draft("room"))
        drafts.flush(); runCurrent()
        assertNull(store(storage).awaitDraft("room"))
    }

    @Test
    fun clearRemovesDraft() = runTest {
        val storage = MemoryStorage()
        val drafts = store(storage)
        drafts.update("room", "送った")
        drafts.flush(); runCurrent()
        drafts.clear("room")
        drafts.flush(); runCurrent()
        assertNull(store(storage).awaitDraft("room"))
    }

    @Test
    fun editsBeforeLoadWinOverStoredDraft() = runTest {
        val storage = MemoryStorage()
        store(storage).apply { update("room", "古い"); update("other", "残る"); flush() }
        runCurrent()
        val drafts = store(storage)
        drafts.update("room", "新しい")
        assertEquals("新しい", drafts.awaitDraft("room")?.text)
        assertEquals("残る", drafts.awaitDraft("other")?.text)
    }

    @Test
    fun accountsDoNotShareDrafts() = runTest {
        val storage = MemoryStorage()
        store(storage, owner = "alice").apply { update("room", "alice の下書き"); flush() }
        runCurrent()
        assertNull(store(storage, owner = "bob").awaitDraft("room"))
    }

    @Test
    fun dropsOldAndExcessDrafts() = runTest {
        val storage = MemoryStorage()
        var now = 0L
        val drafts = ChatDraftStore("me", backgroundScope, storage, now = { now }, saveDelayMillis = 500, maxDrafts = 3, maxAgeSeconds = 100)
        drafts.update("old", "古い")
        now = 150
        drafts.update("a", "1"); now++
        drafts.update("b", "2"); now++
        drafts.update("c", "3"); now++
        drafts.update("d", "4")
        drafts.flush(); runCurrent()
        val reloaded = ChatDraftStore("me", backgroundScope, storage, now = { now }, saveDelayMillis = 500, maxDrafts = 3, maxAgeSeconds = 100)
        assertEquals(setOf("b", "c", "d"), reloaded.awaitAll().keys)
    }

    @Test
    fun brokenStoredValueIsIgnored() = runTest {
        val storage = MemoryStorage()
        storage.rows[chatDraftStorageKey("me")] = "{ not json"
        val drafts = store(storage)
        assertTrue(drafts.awaitAll().isEmpty())
        drafts.update("room", "書ける")
        drafts.flush(); runCurrent()
        assertEquals("書ける", store(storage).awaitDraft("room")?.text)
    }
}
