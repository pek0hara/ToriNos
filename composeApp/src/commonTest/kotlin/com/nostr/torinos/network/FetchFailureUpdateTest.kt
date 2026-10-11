package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetchFailureUpdateTest {
    @Test
    fun failingRelayWithoutAnAnswerBecomesUnavailable() {
        val update = fetchFailureUpdate(
            expected = setOf(GOOD, DEAD),
            outcomes = emptyMap(),
            unavailable = mapOf(DEAD to failure("UnknownHostException")),
            anyRelayConnected = true,
        )

        assertEquals(mapOf(DEAD to RelayOutcome.Unavailable("UnknownHostException")), update.unavailable)
        // 応答するリレーはまだ答えていないので、取得は続ける。
        assertFalse(update.settled)
    }

    @Test
    fun fetchSettlesWhenTheRemainingRelaysAreAllFailing() {
        val update = fetchFailureUpdate(
            expected = setOf(GOOD, DEAD, NOT_WEBSOCKET),
            outcomes = mapOf(GOOD to RelayOutcome.Eose),
            unavailable = mapOf(DEAD to failure("dns"), NOT_WEBSOCKET to failure("handshake")),
            anyRelayConnected = true,
        )

        assertEquals(setOf(DEAD, NOT_WEBSOCKET), update.unavailable.keys)
        assertTrue(update.settled)
    }

    @Test
    fun fetchSettlesAtOnceWhenEveryTargetIsUnavailableButAnotherRelayIsConnected() {
        // 1つのリレーだけに聞く取得（フィードのページなど）で、ほかのリレーはつながっている。
        val update = fetchFailureUpdate(
            expected = setOf(GOOD, DEAD),
            outcomes = emptyMap(),
            unavailable = mapOf(GOOD to failure("offline"), DEAD to failure("offline")),
            anyRelayConnected = true,
        )

        assertEquals(setOf(GOOD, DEAD), update.unavailable.keys)
        assertTrue(update.settled)
    }

    @Test
    fun relaysThatAlreadyAnsweredAreKept() {
        // EOSE や CLOSED を返した後で接続が切れて再接続に失敗しても、その結果は変えない。
        val update = fetchFailureUpdate(
            expected = setOf(GOOD, CLOSED),
            outcomes = mapOf(GOOD to RelayOutcome.Eose, CLOSED to RelayOutcome.Closed("blocked")),
            unavailable = mapOf(GOOD to failure("dropped"), CLOSED to failure("dropped")),
            anyRelayConnected = true,
        )

        assertEquals(emptyMap(), update.unavailable)
        assertTrue(update.settled)
    }

    @Test
    fun failuresOutsideTheFetchAreIgnored() {
        val update = fetchFailureUpdate(
            expected = setOf(GOOD),
            outcomes = emptyMap(),
            unavailable = mapOf(DEAD to failure("dns")),
            anyRelayConnected = true,
        )

        assertEquals(emptyMap(), update.unavailable)
        assertFalse(update.settled)
    }

    @Test
    fun nothingChangesWithoutFailures() {
        val update = fetchFailureUpdate(
            expected = setOf(GOOD, DEAD),
            outcomes = mapOf(GOOD to RelayOutcome.Eose),
            unavailable = emptyMap(),
            anyRelayConnected = true,
        )

        assertEquals(emptyMap(), update.unavailable)
        assertFalse(update.settled)
    }

    @Test
    fun nothingChangesWhileNoRelayIsConnected() {
        // オフラインや復帰の直後は端末側の通信の問題とみなし、今までどおり時間切れまで待つ。
        val update = fetchFailureUpdate(
            expected = setOf(GOOD, DEAD),
            outcomes = emptyMap(),
            unavailable = mapOf(GOOD to failure("offline"), DEAD to failure("offline")),
            anyRelayConnected = false,
        )

        assertEquals(emptyMap(), update.unavailable)
        assertFalse(update.settled)
    }

    private fun failure(reason: String) = RelayConnectionFailure(reason = reason, atMillis = 0L)

    private companion object {
        const val GOOD = "wss://good.example"
        const val DEAD = "wss://dead.example"
        const val NOT_WEBSOCKET = "wss://not-websocket.example"
        const val CLOSED = "wss://closed.example"
    }
}
