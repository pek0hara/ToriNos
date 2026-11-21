package com.nostr.torinos.ui.chat

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrollUnlessPreemptedTest {
    @Test
    fun scrollRefusedDuringUserDragDoesNotEndTheCaller() = runTest {
        val state = ScrollableState { it }
        val release = CompletableDeferred<Unit>()
        val drag = launch { state.scroll(MutatePriority.UserInput) { release.await() } }
        runCurrent()

        // 指で動かしている間の scrollBy は断られる。呼んだ側の監視はそれで終わってはいけない。
        var continued = false
        state.scrollUnlessPreempted { scrollBy(10f) }
        continued = true
        assertTrue(continued)

        release.complete(Unit)
        drag.join()
    }

    @Test
    fun scrollCancelledByLaterScrollDoesNotEndTheCaller() = runTest {
        val state = ScrollableState { it }
        val release = CompletableDeferred<Unit>()
        var after = 0
        val ours = launch {
            state.scrollUnlessPreempted { scroll { release.await() } }
            after++
        }
        runCurrent()
        // 後から来たスクロール（一番下へ追う処理や指）が先のスクロールを打ち切る。
        state.scrollBy(5f)
        runCurrent()

        ours.join()
        assertEquals(1, after)
    }
}
