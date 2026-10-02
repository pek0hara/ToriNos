package com.nostr.torinos.badge

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountBadgeStoreTest {
    private class Fixture(private val scope: kotlinx.coroutines.CoroutineScope) {
        var events = listOf(badgeAward(), badgeDefinition())
        var complete = true
        var active = true
        var failWrite = false
        var targets = setOf("wss://one.example", "wss://two.example")
        val storage = mutableMapOf<String, String>()
        val sent = mutableListOf<NostrEvent>()
        var send: suspend (NostrEvent, Set<String>) -> RelayPublishResult = { event, relays -> sent += event; RelayPublishResult(relays, emptyMap()) }
        var counter = 0
        val signer = object : AccountSigner {
            override val pubkey = badgeOwner
            override fun encryptToSelf(plaintext: String) = plaintext
            override fun decrypt(content: String, peerPubkey: String) = content
            override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?): NostrEvent = badgeEvent(kind, tags = tags, time = createdAt!!, id = (++counter).toString(16).padStart(64, '0'), content = content)
        }
        val transport = BadgeTransport(open = { badgeResponse(it, events, complete) }, validate = { true }, send = { event, urls -> send(event, urls) },
            validationDispatcher = scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher)
        val repo = BadgeRepository(scope, transport, readRelays = { setOf("wss://one.example") })
        fun store() = AccountBadgeStore(badgeOwner, "session", signer, scope, repo,
            relays = { setOf("wss://one.example") }, writableRelays = { targets },
            ensureActive = { check(active) { "account switched" } },
            readStorage = { storage[it] }, writeStorage = { key, value -> check(!failWrite); if (value == null) storage.remove(key) else storage[key] = value },
            validate = { true }, now = { 100 })
    }
    @Test fun firstSelectionCanSaveAfterConfirmedAbsence() = runTest {
        val f = Fixture(backgroundScope); val store = f.store()
        assertTrue(store.refreshSelection())
        assertTrue(store.state.value.ready)
        store.loadAwards()
        assertTrue(store.save(store.draft().copy(items = listOf(badgePair()))), store.state.value.message)
        assertEquals(10008, store.state.value.latest!!.kind)
        assertEquals(100L, store.state.value.latest!!.createdAt)
        assertTrue(f.storage.isNotEmpty())
        store.close()
    }
    @Test fun failedFetchDoesNotAllowFirstSave() = runTest {
        val f = Fixture(backgroundScope); f.complete = false; val store = f.store()
        assertFalse(store.refreshSelection())
        assertFalse(store.state.value.ready)
        assertFalse(store.save(store.draft()))
        assertTrue(f.storage.isEmpty())
        store.close()
    }
    @Test fun sequentialPendingSavesHaveStrictlyIncreasingTimestampAndPreserveContent() = runTest {
        val f = Fixture(backgroundScope)
        f.events = f.events + badgeEvent(10008, tags = listOf(listOf("unknown", "keep")) + badgePair().tags, time = 10, id = "9".repeat(64), content = "encrypted-content")
        val store = f.store(); store.refreshSelection()
        assertTrue(store.save(store.draft()))
        val first = store.state.value.latest!!
        assertTrue(store.save(store.draft().copy(items = emptyList())))
        val second = store.state.value.latest!!
        assertEquals(first.createdAt + 1, second.createdAt)
        assertEquals("encrypted-content", second.content)
        assertEquals(listOf(listOf("unknown", "keep")), second.tags)
        store.close()
    }
    @Test fun remoteConflictKeepsDraftAndDoesNotPublish() = runTest {
        val f = Fixture(backgroundScope); val store = f.store(); store.refreshSelection()
        val draft = store.draft().copy(items = listOf(badgePair()))
        f.events = f.events + badgeEvent(10008, time = 5)
        assertFalse(store.save(draft))
        assertTrue(f.sent.isEmpty())
        assertEquals(1, draft.items.size)
        store.close()
    }
    @Test fun persistenceFailureDoesNotReplacePreviousSelection() = runTest {
        val f = Fixture(backgroundScope); val store = f.store(); store.refreshSelection(); store.loadAwards(); store.save(store.draft())
        val original = store.state.value.latest
        f.failWrite = true
        assertFalse(store.save(store.draft().copy(items = listOf(badgePair()))))
        assertEquals(original, store.state.value.latest)
        store.close()
    }
    @Test fun partialAcceptancePersistsOnlyRemainingTargetsAndRestoresAcrossRestart() = runTest {
        val f = Fixture(backgroundScope)
        f.send = { event, _ -> f.sent += event; RelayPublishResult(setOf("wss://one.example"), mapOf("wss://two.example" to "failed")) }
        val store = f.store(); store.refreshSelection(); store.save(store.draft()); store.retryPending()
        assertEquals("一部のリレーに未同期", store.state.value.message)
        val restored = f.store(); restored.initialize()
        assertEquals(store.state.value.latest, restored.state.value.latest)
        var retried = emptySet<String>()
        f.send = { _, urls -> retried = urls; RelayPublishResult(urls, emptyMap()) }
        restored.retryPending()
        assertEquals(setOf("wss://two.example"), retried)
        assertEquals("保存済み", restored.state.value.message)
        restored.close(); store.close()
    }
    @Test fun zeroWriteRelaysDoesNotClaimSyncedAndAccountSwitchRejectsSave() = runTest {
        val f = Fixture(backgroundScope); f.targets = emptySet(); val store = f.store(); store.refreshSelection()
        assertTrue(store.save(store.draft()))
        store.retryPending()
        assertEquals("送信先が未設定です", store.state.value.message)
        f.active = false
        assertFalse(store.save(store.draft()))
        assertTrue(f.sent.isEmpty())
        store.close()
    }
    @Test fun oldOkCannotEraseNewPendingEvent() = runTest {
        val f = Fixture(backgroundScope); val store = f.store(); store.refreshSelection(); store.loadAwards(); store.save(store.draft())
        val first = store.state.value.latest!!
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        f.send = { event, urls ->
            if (event.id == first.id) { entered.complete(Unit); finish.await(); RelayPublishResult(urls, emptyMap()) }
            else RelayPublishResult(emptySet(), urls.associateWith { "failed" })
        }
        val retry = backgroundScope.async { store.retryPending() }
        entered.await()
        assertTrue(store.save(store.draft().copy(items = listOf(badgePair()))))
        val second = store.state.value.latest!!
        finish.complete(Unit); retry.await()
        val restored = f.store(); restored.initialize()
        assertEquals(second.id, restored.state.value.latest!!.id)
        assertNotEquals("保存済み", restored.state.value.message)
        restored.close(); store.close()
    }
}
