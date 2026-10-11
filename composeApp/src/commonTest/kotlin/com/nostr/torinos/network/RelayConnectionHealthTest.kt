package com.nostr.torinos.network

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RelayConnectionHealthTest {
    private var now = 1_000L
    private val health = RelayConnectionHealth(nowMillis = { now })

    private val state get() = health.state.value

    @Test
    fun firstAttemptIsNotUnavailable() {
        // 1回目の接続中は外さない。数ミリ秒差でつながるリレーを外すと、あとから投稿が割り込むため。
        health.onAttemptStarted()

        assertNull(state.failure)
        assertTrue(state.attempting)
        assertFalse(state.isUnavailable)
    }

    @Test
    fun failureBeforeConnectingMakesTheRelayUnavailable() {
        health.onAttemptStarted()
        health.onFailedBeforeConnecting(IllegalStateException("Unable to resolve host"))

        assertEquals(
            RelayConnectionFailure(reason = "IllegalStateException: Unable to resolve host", atMillis = 1_000L),
            state.failure,
        )
        assertFalse(state.attempting)
        assertTrue(state.isUnavailable)
    }

    @Test
    fun retryAttemptWaitsForItsResult() {
        health.onAttemptStarted()
        health.onFailedBeforeConnecting(IllegalStateException("first"))

        // 復帰の直後などは、前の失敗が残ったまま再接続が始まり、すぐつながることが多い。その結果を待つ。
        health.onAttemptStarted()

        assertTrue(state.failure != null)
        assertFalse(state.isUnavailable)
    }

    @Test
    fun laterFailuresKeepTheFirstFailure() {
        health.onAttemptStarted()
        health.onFailedBeforeConnecting(IllegalStateException("first"))
        val first = state.failure

        // 再試行がまた失敗しても値を変えない。配信中の購読への通知は「失敗し始めた」ときだけ出す。
        now = 5_000L
        health.onAttemptStarted()
        health.onFailedBeforeConnecting(IllegalStateException("second"))

        assertSame(first, state.failure)
        assertTrue(state.isUnavailable)
    }

    @Test
    fun connectingClearsTheFailure() {
        health.onAttemptStarted()
        health.onFailedBeforeConnecting(IllegalStateException("refused"))
        health.onAttemptStarted()

        health.onConnected()

        assertEquals(RelayHealth(), state)
    }

    @Test
    fun droppingAfterConnectingIsNotAFailure() {
        health.onAttemptStarted()
        health.onConnected()

        // つながった後に切れて、次の接続を試みている間。
        health.onAttemptStarted()

        assertNull(state.failure)
        assertFalse(state.isUnavailable)
    }

    @Test
    fun failureAfterReconnectingIsRecordedAgain() {
        health.onAttemptStarted()
        health.onFailedBeforeConnecting(IllegalStateException("first"))
        health.onAttemptStarted()
        health.onConnected()

        now = 9_000L
        health.onAttemptStarted()
        health.onFailedBeforeConnecting(IllegalStateException("again"))

        assertEquals(RelayConnectionFailure("IllegalStateException: again", 9_000L), state.failure)
    }

    @Test
    fun cancellationIsNotAFailure() {
        // disconnect() や画面を閉じたときの中断は、リレーの失敗ではない。
        health.onAttemptStarted()
        health.onFailedBeforeConnecting(CancellationException("closed"))

        assertNull(state.failure)
        assertFalse(state.isUnavailable)
    }

    @Test
    fun resetClearsTheFailure() {
        health.onAttemptStarted()
        health.onFailedBeforeConnecting(IllegalStateException("refused"))

        health.reset()

        assertEquals(RelayHealth(), state)
    }

    @Test
    fun reasonIsShortened() {
        health.onAttemptStarted()
        health.onFailedBeforeConnecting(IllegalStateException("x".repeat(500)))

        val reason = state.failure?.reason.orEmpty()
        assertTrue(reason.startsWith("IllegalStateException: x"))
        assertEquals(120, reason.length)
    }

    @Test
    fun reasonWithoutMessageIsTheClassName() {
        health.onAttemptStarted()
        health.onFailedBeforeConnecting(IllegalStateException())

        assertEquals("IllegalStateException", state.failure?.reason)
    }
}
