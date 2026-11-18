package com.nostr.torinos.dm

import com.nostr.torinos.account.AccountSessionLease
import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.account.PrivateKeyAccountSigner
import com.nostr.torinos.account.SignerException
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class DmInboxTest {
    private val meKey = "0000000000000000000000000000000000000000000000000000000000000003"
    private val bobKey = "b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef"
    private val carolKey = "c90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b14e5c9"
    private val meSigner = PrivateKeyAccountSigner(meKey, AccountSessionLease())
    private val bob = PrivateKeyAccountSigner(bobKey, AccountSessionLease())
    private val carol = PrivateKeyAccountSigner(carolKey, AccountSessionLease())
    private val me = meSigner.pubkey
    private val now = 1_800_000_000L

    /** 復号の回数を数え、指定したら失敗させる署名器。 */
    private class CountingSigner(private val inner: AccountSigner) : AccountSigner by inner {
        var decrypts = 0
        var failWith: SignerException? = null
        override suspend fun nip44Decrypt(payload: String, peerPubkey: String): String {
            failWith?.let { throw it }
            decrypts++
            return inner.nip44Decrypt(payload, peerPubkey)
        }
    }

    private class FakeFetcher : DmEventFetcher {
        val events = mutableListOf<NostrEvent>()
        val calls = mutableListOf<Pair<Set<String>, List<NostrFilter>>>()
        /** 遅いリレー（認証待ちなど）の分。[gate] が開くまで届かない。gift wrap の取得だけに当てる。 */
        val lateEvents = mutableListOf<NostrEvent>()
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun fetch(
            relays: Set<String>,
            filters: List<NostrFilter>,
            onEvent: suspend (NostrEvent) -> Unit,
        ): List<NostrEvent> {
            calls += relays to filters
            val now = events.filter { event -> filters.any { it.matches(event) } }
            now.forEach { onEvent(it) }
            val isWrapFetch = filters.any { GIFT_WRAP_KIND in it.kinds.orEmpty() }
            val late = if (isWrapFetch) {
                gate?.await()
                lateEvents.filter { event -> filters.any { it.matches(event) } }.onEach { onEvent(it) }
            } else {
                emptyList()
            }
            return now + late
        }

        private fun NostrFilter.matches(e: NostrEvent): Boolean =
            (kinds == null || e.kind in kinds) &&
                (authors == null || e.pubkey in authors) &&
                (pTags == null || e.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) in pTags }) &&
                (since == null || e.createdAt >= since)
    }

    private class FakePublisher : DmPublisher {
        val sent = mutableListOf<Pair<NostrEvent, Set<String>>>()
        /** 受理しないリレー。 */
        val down = mutableSetOf<String>()
        override suspend fun publish(event: NostrEvent, relays: Set<String>): Set<String> {
            sent += event to relays
            return relays - down
        }
    }

    private class MemorySettings : DmSettings {
        val values = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = values[key]
        override suspend fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private suspend fun dm(from: AccountSigner, text: String, at: Long, to: String = me, wrapAt: Long = at): NostrEvent =
        wrapRumor(buildDmRumor(from.pubkey, listOf(to), text, at), to, from, wrapAt, Random(at))

    private fun relayList(vararg urls: String) =
        signEvent(meKey, "", kind = DM_RELAY_LIST_KIND, tags = urls.map { listOf("relay", it) }, createdAt = now - 10)

    private class Fixture(
        val inbox: DmInbox,
        val fetcher: FakeFetcher,
        val publisher: FakePublisher,
        val signer: CountingSigner,
        val settings: MemorySettings,
        val follows: MutableStateFlow<Set<String>>,
        val muted: MutableStateFlow<Set<String>>,
        var clock: Long,
    )

    private fun TestScope.fixture(readRelays: Set<String> = setOf("wss://read.example"), clockStart: Long = now): Fixture {
        val fetcher = FakeFetcher()
        val publisher = FakePublisher()
        val signer = CountingSigner(meSigner)
        val settings = MemorySettings()
        val follows = MutableStateFlow(setOf(bob.pubkey))
        val muted = MutableStateFlow(emptySet<String>())
        lateinit var f: Fixture
        val inbox = DmInbox(
            scope = backgroundScope,
            signer = signer,
            readRelays = { readRelays },
            follows = follows,
            muted = muted,
            fetcher = fetcher,
            settings = settings,
            publisher = publisher,
            now = { f.clock },
            random = Random(7),
        )
        f = Fixture(inbox, fetcher, publisher, signer, settings, follows, muted, clockStart)
        return f
    }

    @Test
    fun readsGiftWrapsOnlyFromTheDmRelayList() = runTest {
        val f = fixture()
        f.fetcher.events += relayList("wss://dm.example")
        f.fetcher.events += dm(bob, "hi", now - 100)
        f.inbox.refresh()

        assertEquals(setOf("wss://dm.example"), f.inbox.dmRelays.value)
        assertEquals(true, f.inbox.state.value.hasRelayList)
        val wrapCalls = f.fetcher.calls.filter { (_, filters) -> filters.any { GIFT_WRAP_KIND in it.kinds.orEmpty() } }
        assertTrue(wrapCalls.isNotEmpty())
        assertTrue(wrapCalls.all { it.first == setOf("wss://dm.example") })
        assertEquals(listOf(me), wrapCalls.first().second.single().pTags)
        assertEquals("hi", f.inbox.state.value.lists.conversations.single().last.content)
        assertIs<DmInboxStatus.Ready>(f.inbox.state.value.status)
    }

    @Test
    fun relayListIsReadFromReadRelays() = runTest {
        val f = fixture(readRelays = setOf("wss://read.example"))
        f.fetcher.events += relayList("wss://dm.example")
        f.inbox.refresh()
        val listCall = f.fetcher.calls.first { (_, filters) -> filters.any { DM_RELAY_LIST_KIND in it.kinds.orEmpty() } }
        assertEquals(setOf("wss://read.example"), listCall.first)
        assertEquals(listOf(me), listCall.second.single().authors)
    }

    @Test
    fun withoutRelayListFallsBackToReadRelays() = runTest {
        val f = fixture(readRelays = setOf("wss://read.example"))
        f.fetcher.events += dm(bob, "hi", now - 100)
        f.inbox.refresh()
        assertEquals(false, f.inbox.state.value.hasRelayList)
        assertEquals(setOf("wss://read.example"), f.inbox.dmRelays.value)
        assertEquals(1, f.inbox.state.value.lists.conversations.size)
    }

    @Test
    fun brokenOrForeignWrapsAreSkipped() = runTest {
        val f = fixture()
        f.fetcher.events += dm(bob, "ok", now - 100)
        // carol 宛て（p が自分でない）は取得のフィルターで落ちる。自分宛てで中身が壊れたもの。
        f.fetcher.events += signEvent(carolKey, "broken", kind = GIFT_WRAP_KIND, tags = listOf(listOf("p", me)), createdAt = now - 50)
        f.inbox.refresh()
        assertEquals(listOf("ok"), f.inbox.state.value.lists.conversations.map { it.last.content })
    }

    @Test
    fun secondRefreshUsesSinceAndDoesNotReopen() = runTest {
        val f = fixture()
        val first = dm(bob, "one", now - 100, wrapAt = now - 100)
        f.fetcher.events += first
        f.inbox.refresh()
        val firstDecrypts = f.signer.decrypts
        assertEquals(2, firstDecrypts)

        f.fetcher.events += dm(bob, "two", now - 10, wrapAt = now - 10)
        f.inbox.refresh()
        val lastWrapCall = f.fetcher.calls.last { (_, filters) -> filters.any { GIFT_WRAP_KIND in it.kinds.orEmpty() } }
        // 前回までに受け取った gift wrap の最も新しい時刻からさかのぼる。
        assertEquals(giftWrapSince(first.createdAt), lastWrapCall.second.single().since)
        // 2通目だけを開封する（1件に2回の復号）。
        assertEquals(firstDecrypts + 2, f.signer.decrypts)
        assertEquals("two", f.inbox.state.value.lists.conversations.single().last.content)
    }

    @Test
    fun signerFailureStopsAndRetriesOnNextRefresh() = runTest {
        val f = fixture()
        f.fetcher.events += dm(bob, "hi", now - 100)
        f.signer.failWith = SignerException.Timeout()
        f.inbox.refresh()
        assertIs<DmInboxStatus.Failed>(f.inbox.state.value.status)
        assertTrue(f.inbox.state.value.lists.conversations.isEmpty())

        f.signer.failWith = null
        f.inbox.refresh()
        assertEquals(1, f.inbox.state.value.lists.conversations.size)
        assertIs<DmInboxStatus.Ready>(f.inbox.state.value.status)
    }

    @Test
    fun firstLoadDoesNotMarkOldMessagesUnreadButLaterOnesAre() = runTest {
        val f = fixture()
        f.fetcher.events += dm(bob, "old", now - 100)
        f.inbox.refresh()
        assertFalse(f.inbox.state.value.lists.conversations.single().unread)

        f.clock = now + 1_000
        f.fetcher.events += dm(bob, "new", now + 500)
        f.inbox.refresh()
        val conversation = f.inbox.state.value.lists.conversations.single()
        assertTrue(conversation.unread)
        assertEquals(1, f.inbox.state.value.lists.unreadConversationCount)

        f.inbox.markRead(conversation.key)
        runCurrent()
        assertFalse(f.inbox.state.value.lists.conversations.single().unread)
        assertTrue(f.settings.values.keys.any { it.startsWith("dm.lastRead.") })
    }

    @Test
    fun baselineSurvivesAcrossInboxesThroughSettings() = runTest {
        val f = fixture()
        f.inbox.refresh()
        val stored = f.settings.values.filterKeys { it.startsWith("dm.readBaseline.") }
        assertEquals(1, stored.size)
        assertEquals(now.toString(), stored.values.single())
    }

    @Test
    fun followAndMuteChangesRebuildTheLists() = runTest {
        val f = fixture()
        f.fetcher.events += dm(carol, "hello", now - 100)
        // フォロー・ミュートの変化を見始めるのは start から。
        f.inbox.start()
        runCurrent()
        assertEquals(1, f.inbox.state.value.lists.requests.size)

        f.follows.value = setOf(bob.pubkey, carol.pubkey)
        runCurrent()
        assertEquals(1, f.inbox.state.value.lists.conversations.size)
        assertTrue(f.inbox.state.value.lists.requests.isEmpty())

        f.muted.value = setOf(carol.pubkey)
        runCurrent()
        assertTrue(f.inbox.state.value.lists.conversations.isEmpty())
    }

    @Test
    fun startIsIdempotent() = runTest {
        val f = fixture()
        f.inbox.start()
        f.inbox.start()
        runCurrent()
        assertEquals(1, f.fetcher.calls.count { (_, filters) -> filters.any { DM_RELAY_LIST_KIND in it.kinds.orEmpty() } })
    }

    private fun peerRelayList(peerKey: String, vararg urls: String) =
        signEvent(peerKey, "", kind = DM_RELAY_LIST_KIND, tags = urls.map { listOf("relay", it) }, createdAt = now - 10)

    @Test
    fun sendWrapsForPeerAndSelfAndPublishesToEachDmRelays() = runTest {
        val f = fixture()
        f.fetcher.events += relayList("wss://my-dm.example")
        f.fetcher.events += peerRelayList(bobKey, "wss://bob-dm.example")
        f.inbox.refresh()

        val result = f.inbox.send(listOf(bob.pubkey), "こんにちは")
        assertIs<DmSendResult.Sent>(result)
        assertEquals(2, f.publisher.sent.size)
        val toBob = f.publisher.sent.single { it.second == setOf("wss://bob-dm.example") }.first
        val toMe = f.publisher.sent.single { it.second == setOf("wss://my-dm.example") }.first
        assertEquals(listOf(listOf("p", bob.pubkey)), toBob.tags)
        assertEquals(listOf(listOf("p", me)), toMe.tags)
        // 相手は自分の鍵で開ける。
        val opened = assertIs<UnwrapResult.Opened>(unwrapGiftWrap(toBob, bob))
        assertEquals("こんにちは", opened.rumor.content)
        assertEquals(me, opened.rumor.pubkey)
        // 送った分はすぐ一覧と会話に出る。
        val conversation = f.inbox.state.value.lists.conversations.single()
        assertEquals("こんにちは", conversation.last.content)
        assertEquals(DmSendStatus.Sent, f.inbox.sendStatus.value[opened.rumor.id])
    }

    @Test
    fun sentSelfCopyIsNotReopenedOnRefresh() = runTest {
        val f = fixture()
        f.fetcher.events += relayList("wss://my-dm.example")
        f.fetcher.events += peerRelayList(bobKey, "wss://bob-dm.example")
        f.inbox.refresh()
        f.inbox.send(listOf(bob.pubkey), "x")
        val selfCopy = f.publisher.sent.single { it.second == setOf("wss://my-dm.example") }.first
        f.fetcher.events += selfCopy
        val before = f.signer.decrypts
        f.inbox.refresh()
        assertEquals(before, f.signer.decrypts)
        assertEquals(1, f.inbox.state.value.lists.conversations.single().messageCount)
    }

    @Test
    fun peerWithoutRelayListIsNotSentTo() = runTest {
        val f = fixture()
        f.fetcher.events += relayList("wss://my-dm.example")
        f.inbox.refresh()
        val result = f.inbox.send(listOf(bob.pubkey), "hi")
        assertEquals(DmSendResult.NoRelayList(listOf(bob.pubkey)), result)
        assertTrue(f.publisher.sent.isEmpty())
        assertTrue(f.inbox.state.value.lists.conversations.isEmpty())
    }

    @Test
    fun peerRelayListIsLookedUpOnReadAndHintRelays() = runTest {
        val f = fixture(readRelays = setOf("wss://read.example"))
        f.fetcher.events += peerRelayList(bobKey, "wss://bob-dm.example")
        f.inbox.send(listOf(bob.pubkey), "hi")
        val lookup = f.fetcher.calls.first { (_, filters) -> filters.any { it.authors == listOf(bob.pubkey) } }
        assertTrue("wss://read.example" in lookup.first)
    }

    @Test
    fun failedDeliveryCanBeRetriedWithTheSameWrap() = runTest {
        val f = fixture()
        f.fetcher.events += relayList("wss://my-dm.example")
        f.fetcher.events += peerRelayList(bobKey, "wss://bob-dm.example")
        f.inbox.refresh()
        f.publisher.down += "wss://bob-dm.example"
        val result = f.inbox.send(listOf(bob.pubkey), "hi")
        val failed = assertIs<DmSendResult.Failed>(result)
        assertIs<DmSendStatus.Failed>(f.inbox.sendStatus.value[failed.rumorId])
        val firstWrap = f.publisher.sent.first { it.second == setOf("wss://bob-dm.example") }.first

        f.publisher.down.clear()
        f.inbox.retry(failed.rumorId)
        runCurrent()
        val resent = f.publisher.sent.last { it.second == setOf("wss://bob-dm.example") }.first
        assertEquals(firstWrap, resent)
        assertEquals(DmSendStatus.Sent, f.inbox.sendStatus.value[failed.rumorId])
    }

    @Test
    fun signerRefusalDoesNotLeaveAPendingMessage() = runTest {
        val f = fixture()
        f.fetcher.events += peerRelayList(bobKey, "wss://bob-dm.example")
        val refusing = object : AccountSigner by meSigner {
            override suspend fun nip44Encrypt(plaintext: String, peerPubkey: String): String = throw SignerException.Rejected()
        }
        val inbox = DmInbox(
            scope = backgroundScope, signer = refusing, readRelays = { setOf("wss://read.example") },
            follows = f.follows, muted = f.muted, fetcher = f.fetcher, settings = f.settings, publisher = f.publisher,
            now = { now },
        )
        val result = inbox.send(listOf(bob.pubkey), "hi")
        assertIs<DmSendResult.Refused>(result)
        assertTrue(f.publisher.sent.isEmpty())
        assertTrue(inbox.state.value.lists.conversations.isEmpty())
    }

    @Test
    fun noteToSelfOnlySendsTheSelfCopy() = runTest {
        val f = fixture()
        f.fetcher.events += relayList("wss://my-dm.example")
        f.inbox.refresh()
        assertIs<DmSendResult.Sent>(f.inbox.send(listOf(me), "memo"))
        assertEquals(listOf(setOf("wss://my-dm.example")), f.publisher.sent.map { it.second })
    }

    @Test
    fun blankMessageIsNotSent() = runTest {
        val f = fixture()
        assertEquals(DmSendResult.Empty, f.inbox.send(listOf(bob.pubkey), "  \n "))
        assertTrue(f.publisher.sent.isEmpty())
    }

    @Test
    fun wrapsFromFastRelaysAreShownBeforeASlowRelayAnswers() = runTest {
        val f = fixture()
        f.fetcher.events += dm(bob, "fast", now - 100)
        f.fetcher.lateEvents += dm(bob, "late", now - 50)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.fetcher.gate = gate
        val job = launch { f.inbox.refresh() }
        runCurrent()
        // 認証待ちのリレーが答える前に、届いた分は一覧に出ている。
        assertEquals("fast", f.inbox.state.value.lists.conversations.single().last.content)
        assertFalse(f.inbox.state.value.status is DmInboxStatus.Ready)

        gate.complete(Unit)
        job.join()
        assertEquals("late", f.inbox.state.value.lists.conversations.single().last.content)
        assertEquals(2, f.inbox.state.value.lists.conversations.single().messageCount)
        assertIs<DmInboxStatus.Ready>(f.inbox.state.value.status)
    }

    @Test
    fun aWrapDeliveredByTwoRelaysIsOpenedOnce() = runTest {
        val f = fixture()
        val wrap = dm(bob, "hi", now - 100)
        f.fetcher.events += wrap
        f.fetcher.lateEvents += wrap
        f.fetcher.gate = kotlinx.coroutines.CompletableDeferred(Unit)
        f.inbox.refresh()
        // 1件に2回の復号（gift wrap と seal）だけ。
        assertEquals(2, f.signer.decrypts)
    }
}
