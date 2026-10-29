package com.nostr.torinos.zap

import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ZapSenderTest {
    private val baseTime = 1_700_000_000_000L
    private val target = ZapTarget(noteId, authorPubkey, kind = 1)
    private val zapEndpoint = PayEndpoint(
        url = "https://example.com/.well-known/lnurlp/alice",
        callback = "https://example.com/cb",
        minSendableMsats = 1_000,
        maxSendableMsats = 100_000_000,
        commentAllowed = 100,
        nostrPubkey = pubkeyOf(zapperKey),
    )

    private inner class Fake(scope: TestScope) {
        var profile: NostrProfile? = NostrProfile(lud16 = "alice@example.com")
        var endpoint = zapEndpoint
        var invoiceFor: (Long) -> String = { msats -> invoiceForMsats(msats, timestamp = (baseTime / 1_000)) }
        val callbacks = mutableListOf<String>()
        val signed = mutableListOf<NostrEvent>()
        val awaited = mutableListOf<String>()
        var receipt = CompletableDeferred<Unit>()
        var invoiceGate: CompletableDeferred<Unit>? = null
        val now: () -> Long = { baseTime + scope.currentTime }
        val events = mutableListOf<ZapEvent>()

        val sender = ZapSender(
            scope = scope.backgroundScope,
            deps = ZapSenderDependencies(
                sign = { content, kind, tags ->
                    signEvent(senderKey, content, kind, tags, createdAt = now() / 1_000).also(signed::add)
                },
                profileOf = { profile },
                endpointOf = { url -> check(url == "https://example.com/.well-known/lnurlp/alice"); endpoint },
                httpGet = { url ->
                    callbacks += url
                    invoiceGate?.await()
                    val amount = Url(url).parameters["amount"]!!.toLong()
                    """{"pr":"${invoiceFor(amount)}","routes":[]}"""
                },
                ownReadRelays = { listOf("wss://own") },
                authorReadRelays = { listOf("wss://author") },
                awaitReceipt = { _, _, requestId, _, _ -> awaited += requestId; receipt.await() },
                nowMillis = now,
            ),
            confirmTimeoutMillis = 180_000,
        )

        init {
            scope.backgroundScope.launch { sender.events.collect(events::add) }
        }
    }

    @Test
    fun sendsZapRequestOpensWalletAndConfirms() = runTest {
        val fake = Fake(this)
        runCurrent()
        fake.sender.send(target, 21, comment = "thanks & #1")
        runCurrent()

        val request = fake.signed.single()
        assertEquals(ZAP_REQUEST_KIND, request.kind)
        assertEquals("thanks & #1", request.content)
        assertEquals(listOf("relays", "wss://own", "wss://author"), request.tags.first())
        assertEquals(listOf("amount", "21000"), request.tags[1])
        val callback = Url(fake.callbacks.single())
        assertEquals(Json.encodeToString(NostrEvent.serializer(), request), callback.parameters["nostr"])
        assertEquals("thanks & #1", callback.parameters["comment"])

        val open = fake.events.filterIsInstance<ZapEvent.OpenWallet>().single()
        assertEquals(noteId, open.eventId)
        assertIs<ZapProgress.AwaitingPayment>(fake.sender.progress.value[noteId])
        assertEquals(listOf(request.id), fake.awaited)

        fake.receipt.complete(Unit)
        advanceTimeBy(1_500)
        runCurrent()
        assertNull(fake.sender.progress.value[noteId])
        assertEquals(setOf(noteId), fake.sender.zappedByMe.value)
    }

    @Test
    fun unconfirmedAfterTimeoutButForegroundExtends() = runTest {
        val fake = Fake(this)
        runCurrent()
        fake.sender.send(target, 21)
        runCurrent()
        advanceTimeBy(170_000)
        fake.sender.onAppForeground()
        advanceTimeBy(170_000)
        runCurrent()
        assertIs<ZapProgress.AwaitingPayment>(fake.sender.progress.value[noteId])
        advanceTimeBy(20_000)
        runCurrent()
        assertNull(fake.sender.progress.value[noteId])
        assertTrue(fake.events.filterIsInstance<ZapEvent.Message>().single().text.contains("確認できませんでした"))
        assertTrue(fake.sender.zappedByMe.value.isEmpty())
    }

    @Test
    fun wrongInvoiceAmountIsNotHandedToWallet() = runTest {
        val fake = Fake(this)
        fake.invoiceFor = { msats -> invoiceForMsats(msats + 1_000, timestamp = baseTime / 1_000) }
        runCurrent()
        fake.sender.send(target, 21)
        runCurrent()
        assertTrue(fake.events.none { it is ZapEvent.OpenWallet })
        assertTrue(fake.events.filterIsInstance<ZapEvent.Message>().single().text.contains("額"))
        assertNull(fake.sender.progress.value[noteId])
    }

    @Test
    fun noAddressAndSplitAreRefused() = runTest {
        val fake = Fake(this)
        runCurrent()
        fake.sender.send(target.copy(hasZapSplit = true), 21)
        fake.profile = NostrProfile(name = "no address")
        fake.sender.send(target, 21)
        runCurrent()
        assertEquals(
            listOf("分配の Zap には未対応です", ZapSender.NO_ADDRESS_MESSAGE),
            fake.events.filterIsInstance<ZapEvent.Message>().map { it.text },
        )
        assertTrue(fake.callbacks.isEmpty())
    }

    @Test
    fun amountOutOfRangeIsRefused() = runTest {
        val fake = Fake(this)
        runCurrent()
        fake.sender.send(target, 200_000)
        runCurrent()
        assertEquals("この受け取り先に送れる額は 1〜100000 sats です", fake.events.filterIsInstance<ZapEvent.Message>().single().text)
        assertTrue(fake.signed.isEmpty())
    }

    @Test
    fun plainPaymentNeedsExplicitPermission() = runTest {
        val fake = Fake(this)
        fake.endpoint = zapEndpoint.copy(nostrPubkey = null)
        runCurrent()
        fake.sender.send(target, 21)
        runCurrent()
        assertEquals(ZapSender.PLAIN_PAYMENT_MESSAGE, fake.events.filterIsInstance<ZapEvent.Message>().single().text)
        assertTrue(fake.callbacks.isEmpty())

        fake.sender.send(target, 21, comment = "hi", allowPlainPayment = true)
        runCurrent()
        assertTrue(fake.signed.isEmpty())
        val callback = Url(fake.callbacks.single())
        assertNull(callback.parameters["nostr"])
        assertEquals("hi", callback.parameters["comment"])
        assertEquals(1, fake.events.count { it is ZapEvent.OpenWallet })
        // レシートは来ないので待たない。
        assertNull(fake.sender.progress.value[noteId])
        assertTrue(fake.awaited.isEmpty())
    }

    @Test
    fun cancelBeforeInvoiceStopsWithoutOpeningWallet() = runTest {
        val fake = Fake(this)
        fake.invoiceGate = CompletableDeferred()
        runCurrent()
        fake.sender.send(target, 21)
        runCurrent()
        assertEquals(ZapProgress.RequestingInvoice, fake.sender.progress.value[noteId])
        assertTrue(fake.sender.isRunning(noteId))
        fake.sender.cancel(noteId)
        fake.invoiceGate!!.complete(Unit)
        runCurrent()
        assertFalse(fake.sender.isRunning(noteId))
        assertTrue(fake.events.none { it is ZapEvent.OpenWallet })
    }

    @Test
    fun secondSendWhileRunningIsIgnored() = runTest {
        val fake = Fake(this)
        fake.invoiceGate = CompletableDeferred()
        runCurrent()
        fake.sender.send(target, 21)
        fake.sender.send(target, 21)
        runCurrent()
        fake.invoiceGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, fake.callbacks.size)
    }

    @Test
    fun atMostThreeAwaitingPayments() = runTest {
        val fake = Fake(this)
        runCurrent()
        val ids = (1..4).map { it.toString().repeat(64) }
        ids.forEach { id ->
            fake.sender.send(target.copy(eventId = id), 21)
            runCurrent()
            advanceTimeBy(10)
        }
        assertEquals(ids.drop(1).toSet(), fake.sender.progress.value.keys)
    }

    @Test
    fun receiptWatchFailureAfterWalletIsNotReportedAsSendFailure() = runTest {
        val fake = Fake(this)
        runCurrent()
        fake.sender.send(target, 21)
        runCurrent()
        assertEquals(1, fake.events.filterIsInstance<ZapEvent.OpenWallet>().size)

        // ウォレットへ渡したあとなので、支払いは済んでいるかもしれない。「送れませんでした」と出すと二重に払われる。
        fake.receipt.completeExceptionally(IllegalStateException("relay closed"))
        advanceTimeBy(1_500)
        runCurrent()
        val message = fake.events.filterIsInstance<ZapEvent.Message>().single().text
        assertTrue(message.contains("確認できませんでした"), message)
        assertFalse(message.contains("送れませんでした"), message)
        assertNull(fake.sender.progress.value[noteId])
        assertTrue(fake.sender.zappedByMe.value.isEmpty())
    }
}

class ZapReceiptSubscriptionIdTest {
    @Test
    fun idsDifferPerRequestAndStayWithinRelayLimit() {
        val first = zapReceiptSubscriptionId("a".repeat(64))
        val second = zapReceiptSubscriptionId("b".repeat(64))
        assertTrue(first != second)
        assertEquals(first, zapReceiptSubscriptionId("a".repeat(64)))
        assertTrue(first.length <= 50, first)
    }
}

class LnurlPayRepositoryTest {
    private val url = "https://example.com/.well-known/lnurlp/alice"
    private val body = """{"tag":"payRequest","callback":"https://example.com/cb","minSendable":1000,"maxSendable":2000}"""

    @Test
    fun concurrentRequestsShareOneFetch() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val repository = LnurlPayRepository(backgroundScope, { calls++; gate.await(); body }, now = { currentTime })
        val results = (1..3).map { async { repository.get(url) } }
        runCurrent()
        gate.complete(Unit)
        results.forEach { assertEquals("https://example.com/cb", it.await().callback) }
        assertEquals(1, calls)
        repository.get(url)
        assertEquals(1, calls)
    }

    @Test
    fun failuresAreNotCachedButRateLimited() = runTest {
        var calls = 0
        var fail = true
        val repository = LnurlPayRepository(backgroundScope, {
            calls++
            if (fail) error("network down") else body
        }, now = { currentTime })
        kotlin.test.assertFailsWith<ZapException> { repository.get(url) }
        kotlin.test.assertFailsWith<ZapException> { repository.get(url) }
        assertEquals(1, calls)
        fail = false
        advanceTimeBy(61_000)
        assertEquals(2000, repository.get(url).maxSendableMsats)
        assertEquals(2, calls)
    }

    @Test
    fun cacheExpires() = runTest {
        var calls = 0
        val repository = LnurlPayRepository(backgroundScope, { calls++; body }, now = { currentTime }, ttlMillis = 1_000)
        repository.get(url)
        advanceTimeBy(1_500)
        repository.get(url)
        assertEquals(2, calls)
    }
}
