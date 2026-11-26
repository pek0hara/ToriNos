package com.nostr.torinos.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RelayRetryBackoffTest {
    @Test
    fun delayDoublesUpToTheMaximum() = runTest {
        val backoff = RelayRetryBackoff(initialMillis = 2_000, maxMillis = 30_000)
        val waits = (1..6).map {
            val before = currentTime
            backoff.await()
            currentTime - before
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), waits)
    }

    @Test
    fun resetStartsOverFromTheInitialDelay() = runTest {
        val backoff = RelayRetryBackoff(initialMillis = 2_000, maxMillis = 30_000)
        repeat(4) { backoff.await() }
        backoff.reset()
        val before = currentTime
        backoff.await()
        assertEquals(2_000L, currentTime - before)
    }

    /** 前面に戻ったとき、長く延びた待ちを切り上げてすぐにつなぎ直す（バックグラウンド中は通信できず待ちが延びている）。 */
    @Test
    fun wakeCutsTheCurrentWaitShortAndStartsOverFromTheInitialDelay() = runTest {
        val backoff = RelayRetryBackoff(initialMillis = 2_000, maxMillis = 30_000)
        repeat(5) { backoff.await() }
        val waiting = async { backoff.await() }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(waiting.isCompleted)

        backoff.wake()
        runCurrent()

        assertTrue(waiting.isCompleted)
        val before = currentTime
        backoff.await()
        assertEquals(2_000L, currentTime - before)
    }

    /** 試行中に前面へ戻ったら、その試行が失敗した後の待ちを短くする（まだ通信が戻っていないことがある）。 */
    @Test
    fun wakeDuringAnAttemptShortensTheNextWait() = runTest {
        val backoff = RelayRetryBackoff(initialMillis = 2_000, maxMillis = 30_000)
        repeat(5) { backoff.await() }

        backoff.wake()
        val before = currentTime
        backoff.await()

        assertTrue(currentTime - before <= 2_000L)
    }

    /** 試行中に来た知らせは、その試行がつながったら捨てる。ずっと後に切れたとき、待たずにつなぎ直さないように。 */
    @Test
    fun wakeLeftFromAnAttemptThatConnectedIsForgotten() = runTest {
        val backoff = RelayRetryBackoff(initialMillis = 2_000, maxMillis = 30_000)
        backoff.wake()
        backoff.reset()

        val before = currentTime
        backoff.await()

        assertEquals(2_000L, currentTime - before)
    }
}
