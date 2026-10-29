package com.nostr.torinos.zap

import com.nostr.torinos.engagement.EngagementStore
import com.nostr.torinos.model.NostrProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ZapTallyTest {
    private val lnurlBody = """{"tag":"payRequest","callback":"https://example.com/cb","minSendable":1000,"maxSendable":2000,""" +
        """"allowsNostr":true,"nostrPubkey":"${pubkeyOf(zapperKey)}"}"""

    @Test
    fun countsVerifiedReceiptsAfterZapperIsKnown() = runTest {
        val store = EngagementStore()
        store.retain(this, setOf(noteId))
        val profiles = MutableStateFlow<NostrProfile?>(null)
        var fetches = 0
        val gate = CompletableDeferred<Unit>()
        val endpoints = LnurlPayRepository(backgroundScope, { fetches++; gate.await(); lnurlBody })
        val tally = ZapTally(
            store = store,
            directory = ZapperDirectory(backgroundScope, endpoints),
            profileOf = { profiles.value },
            profileChanges = { profiles },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val totals = mutableListOf<ZapTotal>()
        backgroundScope.launch { tally.observe(noteId, authorPubkey).collect(totals::add) }
        runCurrent()
        assertEquals(listOf(ZapTotal.Empty), totals.distinct())
        assertEquals(0, fetches)

        store.observe(zapReceipt(), "wss://relay")
        store.observe(zapReceipt(zapRequest(key = otherKey), key = otherKey), "wss://relay")
        runCurrent()
        // 受け取り先が分からない作者のレシートは数えない。取りにも行かない。
        assertEquals(ZapTotal.Empty, tally.current(noteId, authorPubkey))
        assertEquals(0, fetches)

        profiles.value = NostrProfile(lud16 = "alice@example.com")
        runCurrent()
        assertEquals(1, fetches)
        gate.complete(Unit)
        runCurrent()
        val expected = ZapTotal(totalMsats = 21_000, count = 1, senders = setOf(pubkeyOf(senderKey)))
        assertEquals(expected, tally.current(noteId, authorPubkey))
        assertEquals(expected, totals.last())
        assertEquals(1, fetches)
    }
}
