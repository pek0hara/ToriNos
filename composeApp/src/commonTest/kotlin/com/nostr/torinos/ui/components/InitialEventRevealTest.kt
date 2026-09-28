package com.nostr.torinos.ui.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InitialEventRevealTest {
    @Test
    fun unstagedContentIsVisibleImmediately() {
        val reveal = InitialEventReveal(staged = false)

        assertTrue(reveal.isContentComposed)
        assertTrue(reveal.isContentVisible)
    }

    @Test
    fun stagedContentBecomesVisibleAfterPreparation() = runTest {
        val reveal = InitialEventReveal(staged = true)
        assertFalse(reveal.isContentComposed)
        assertFalse(reveal.isContentVisible)

        val gate = CompletableDeferred<Unit>()
        launch { reveal.reveal { gate.await() } }
        runCurrent()
        assertTrue(reveal.isContentComposed)
        assertFalse(reveal.isContentVisible)

        gate.complete(Unit)
        runCurrent()
        assertTrue(reveal.isContentVisible)
    }

    @Test
    fun cancellationDuringPreparationStillShowsContent() = runTest {
        // スクロールがユーザー操作で打ち切られた場合に相当する。
        val reveal = InitialEventReveal(staged = true)
        val job = launch { reveal.reveal { CompletableDeferred<Unit>().await() } }
        runCurrent()
        assertTrue(reveal.isContentComposed)

        job.cancel()
        runCurrent()

        assertTrue(reveal.isContentVisible)
    }

    @Test
    fun secondRevealRequestWhilePreparingDoesNotLeaveContentHidden() = runTest {
        // 表示の合図が重なり、進行中の reveal が打ち切られて再実行される場合に相当する。
        val reveal = InitialEventReveal(staged = true)
        val first = launch { reveal.reveal { CompletableDeferred<Unit>().await() } }
        runCurrent()

        first.cancel()
        launch(start = CoroutineStart.UNDISPATCHED) { reveal.reveal() }
        runCurrent()

        assertTrue(reveal.isContentVisible)
    }

    @Test
    fun showNowSkipsPreparation() {
        val reveal = InitialEventReveal(staged = true)

        reveal.showNow()

        assertTrue(reveal.isContentComposed)
        assertTrue(reveal.isContentVisible)
    }
}
