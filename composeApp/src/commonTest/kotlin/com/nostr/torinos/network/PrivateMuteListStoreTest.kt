@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nostr.torinos.network

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivateMuteListStoreTest {
    @Test
    fun failedPublishSurvivesSwitchAndOldRemoteListThenRetries() = runTest {
        val values = mutableMapOf<String, String>()
        val storage = storage(values)
        val old = event("old", 100, "[[\"word\",\"remote\"]]")
        val a = store(storage, fetch = { old }, publish = { _, _ -> RelayPublishResult(emptySet(), mapOf("relay" to "offline")) })
        a.initialize()
        a.start()
        runCurrent()
        a.addNgWord("local")
        runCurrent()
        a.closeAndFlush()
        assertTrue(storage.load(PUBKEY).hasPendingChanges)
        assertEquals(listOf("remote", "local"), storage.load(PUBKEY).ngWords)

        val b = store(storage, pubkey = OTHER_PUBKEY)
        b.initialize()
        assertEquals(emptyList(), b.ngWords.value)
        b.closeAndFlush()

        val sent = mutableListOf<NostrEvent>()
        val returnedA = store(storage, fetch = { old }, publish = { signed, _ ->
            sent += signed
            RelayPublishResult(setOf("relay"), emptyMap())
        })
        returnedA.initialize()
        assertEquals(listOf("remote", "local"), returnedA.ngWords.value)
        returnedA.start()
        runCurrent()
        assertEquals(1, sent.size)
        assertTrue(sent.single().content.contains("local"))
        assertFalse(storage.load(PUBKEY).hasPendingChanges)
        returnedA.closeAndFlush()
    }

    @Test
    fun switchingBeforeSaveCoroutineRunsFlushesTheLatestEdit() = runTest {
        val storage = storage(mutableMapOf())
        val store = store(storage)
        store.initialize()
        store.addNgWord("just-before-switch")
        store.closeAndFlush()
        assertEquals(listOf("just-before-switch"), storage.load(PUBKEY).ngWords)
        assertTrue(storage.load(PUBKEY).hasPendingChanges)
    }

    @Test
    fun olderPublishCompletionCannotClearNewerLocalEdit() = runTest {
        val storage = storage(mutableMapOf())
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<NostrEvent>()
        val store = store(storage, publish = { event, _ ->
            sent += event
            if (sent.size == 1) release.await()
            RelayPublishResult(setOf("relay"), emptyMap())
        })
        store.initialize()
        store.addNgWord("first")
        runCurrent()
        store.addNgWord("second")
        runCurrent()
        assertEquals(listOf("first", "second"), storage.load(PUBKEY).ngWords)
        assertTrue(storage.load(PUBKEY).hasPendingChanges)
        release.complete(Unit)
        runCurrent()
        assertEquals(2, sent.size)
        assertTrue(sent.last().createdAt > sent.first().createdAt)
        assertEquals(listOf("first", "second"), storage.load(PUBKEY).ngWords)
        assertFalse(storage.load(PUBKEY).hasPendingChanges)
        store.closeAndFlush()
    }

    @Test
    fun refreshCannotOverwriteAnEditMadeWhileFetchIsPending() = runTest {
        val storage = storage(mutableMapOf())
        val response = CompletableDeferred<NostrEvent?>()
        val store = store(storage, fetch = { response.await() },
            publish = { _, _ -> RelayPublishResult(emptySet(), emptyMap()) })
        store.initialize()
        store.start()
        runCurrent()
        store.addNgWord("local")
        runCurrent()
        response.complete(event("remote", 100, "[[\"word\",\"old\"]]"))
        runCurrent()
        assertEquals(listOf("local"), store.ngWords.value)
        assertEquals(listOf("local"), storage.load(PUBKEY).ngWords)
        assertTrue(storage.load(PUBKEY).hasPendingChanges)
        store.closeAndFlush()
    }

    @Test
    fun failedFlushCanRestoreUnsavedChangesWhenRollingBack() = runTest {
        val values = mutableMapOf<String, String>()
        var failSave = true
        val storage = PrivateMuteListStorage(values::get) { key, value ->
            if (key.startsWith("private_mute_list_cache_v2_") && failSave) error("disk failure")
            if (value == null) values.remove(key) else values[key] = value
        }
        val original = store(storage)
        original.initialize()
        original.addNgWord("unsaved")
        try { original.closeAndFlush() } catch (_: IllegalStateException) { }
        val restored = store(storage)
        restored.initialize()
        restored.restorePendingChanges(original.pendingSnapshot())
        assertEquals(listOf("unsaved"), restored.ngWords.value)
        failSave = false
        restored.closeAndFlush()
        assertTrue(storage.load(PUBKEY).hasPendingChanges)
        assertEquals(listOf("unsaved"), storage.load(PUBKEY).ngWords)
    }

    @Test
    fun newerRemoteEventDuringPublishRequiresResigningBeforeMarkingSynced() = runTest {
        val storage = storage(mutableMapOf())
        val response = CompletableDeferred<NostrEvent?>()
        val receipt = CompletableDeferred<Unit>()
        val sent = mutableListOf<NostrEvent>()
        val store = store(storage, fetch = { response.await() }, publish = { event, _ ->
            sent += event
            if (sent.size == 1) receipt.await()
            RelayPublishResult(setOf("relay"), emptyMap())
        })
        store.initialize()
        store.start()
        runCurrent()
        store.addNgWord("local")
        runCurrent()
        val remoteTime = sent.single().createdAt + 10
        response.complete(event("newer-remote", remoteTime, "[[\"word\",\"remote\"]]"))
        runCurrent()
        receipt.complete(Unit)
        runCurrent()
        assertEquals(2, sent.size)
        assertTrue(sent.last().createdAt > remoteTime)
        assertEquals(listOf("local"), store.ngWords.value)
        assertFalse(storage.load(PUBKEY).hasPendingChanges)
        store.closeAndFlush()
    }

    private fun TestScope.store(
        storage: PrivateMuteListStorage,
        pubkey: String = PUBKEY,
        fetch: suspend () -> NostrEvent? = { null },
        publish: suspend (NostrEvent, List<String>) -> RelayPublishResult = { _, _ -> RelayPublishResult(setOf("relay"), emptyMap()) },
    ) = PrivateMuteListStore(
        signer = FakeSigner(pubkey), sessionId = "session-$pubkey", scope = backgroundScope,
        writableRelayUrls = { listOf("relay") }, ensureActive = {}, storage = storage,
        fetchRemote = fetch, publish = publish,
    )

    private fun storage(values: MutableMap<String, String>) = PrivateMuteListStorage(values::get) { key, value ->
        if (value == null) values.remove(key) else values[key] = value
    }

    private class FakeSigner(override val pubkey: String) : AccountSigner {
        override fun encryptToSelf(plaintext: String) = plaintext
        override fun decrypt(content: String, peerPubkey: String) = content
        override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?) =
            NostrEvent(id = "signed-$createdAt-$content", pubkey = pubkey, createdAt = createdAt ?: 0,
                kind = kind, tags = tags, content = content, sig = "sig")
    }

    companion object {
        private val PUBKEY = "a".repeat(64)
        private val OTHER_PUBKEY = "b".repeat(64)
        private fun event(id: String, createdAt: Long, content: String) = NostrEvent(
            id = id, pubkey = PUBKEY, createdAt = createdAt, kind = 10000,
            tags = emptyList(), content = content, sig = "sig",
        )
    }
}
