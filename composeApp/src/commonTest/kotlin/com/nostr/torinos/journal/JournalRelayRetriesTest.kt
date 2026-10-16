package com.nostr.torinos.journal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class JournalRelayRetriesTest {
    @Test
    fun failingRelayIsRetriedWithBackoffAndGivenUpAfterThreeFailures() = runTest {
        val retries = retries()
        val attempts = mutableListOf<Long>()
        retries.schedule(DEAD) { attempts += testScheduler.currentTime; false }

        runRetries()

        // 最初の失敗の2秒後、2回目の失敗の4秒後に取り直し、3回目の失敗で諦める（フィードと同じ）。
        assertEquals(listOf(2_000L, 6_000L), attempts)
    }

    @Test
    fun worksFailingWhileWaitingShareOneRetry() = runTest {
        val retries = retries()
        val runs = mutableListOf<String>()
        retries.schedule(DEAD) { runs += "date@${testScheduler.currentTime}"; true }
        testScheduler.advanceTimeBy(1_000)
        retries.schedule(DEAD) { runs += "month@${testScheduler.currentTime}"; true }

        runRetries()

        assertEquals(listOf("date@2000", "month@2000"), runs)
    }

    @Test
    fun successResetsTheFailureCount() = runTest {
        val retries = retries()
        val attempts = mutableListOf<Long>()
        retries.schedule(DEAD) { attempts += testScheduler.currentTime; true }
        runRetries()
        val restartedAt = testScheduler.currentTime
        retries.schedule(DEAD) { attempts += testScheduler.currentTime - restartedAt; true }
        runRetries()

        // 成功した後の失敗は1回目として数え、また2秒後に取り直す。
        assertEquals(listOf(2_000L, 2_000L), attempts)
    }

    @Test
    fun relaysAreCountedSeparately() = runTest {
        val retries = retries()
        val runs = mutableListOf<String>()
        retries.schedule(DEAD) { runs += it; false }
        retries.schedule(OTHER_RELAY) { runs += it; true }

        runRetries()

        assertEquals(listOf(DEAD, OTHER_RELAY, DEAD), runs)
    }

    @Test
    fun aWorkThatThrowsCountsAsAFailureAndIsRetried() = runTest {
        val retries = retries()
        val attempts = mutableListOf<Long>()
        retries.schedule(DEAD) {
            attempts += testScheduler.currentTime
            if (attempts.size == 1) throw IllegalStateException("socket closed")
            true
        }

        runRetries()

        assertEquals(listOf(2_000L, 6_000L), attempts)
    }

    @Test
    fun aRelayGivenUpStaysGivenUpUntilCancelAll() = runTest {
        val retries = retries()
        var runs = 0
        retries.schedule(DEAD) { runs += 1; false }
        runRetries()
        retries.schedule(DEAD) { runs += 1; true }
        runRetries()
        assertEquals(2, runs)

        // フィードと同じく、諦めたリレーは取り直しをやめるとき（月の移動など）まで受け付けない。
        retries.cancelAll()
        retries.schedule(DEAD) { runs += 1; true }
        runRetries()
        assertEquals(3, runs)
    }

    @Test
    fun cancelAllDropsPendingRetries() = runTest {
        val retries = retries()
        var runs = 0
        retries.schedule(DEAD) { runs += 1; true }
        retries.cancelAll()

        runRetries()

        assertEquals(0, runs)
    }

    /** 取り直しは`backgroundScope`で動くため、`advanceUntilIdle`では進まない。上限の待ち時間より先まで進める。 */
    private fun TestScope.runRetries() {
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
    }

    private fun TestScope.retries() = JournalRelayRetries(launch = { block -> backgroundScope.launch { block() } })

    private companion object {
        const val DEAD = "wss://dead"
        const val OTHER_RELAY = "wss://other"
    }
}
