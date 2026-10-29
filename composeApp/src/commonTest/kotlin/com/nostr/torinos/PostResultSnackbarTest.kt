package com.nostr.torinos

import androidx.compose.material3.SnackbarHostState
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.ui.post.PostCompletion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PostResultSnackbarTest {
    private val first = RelayPublishResult(setOf("wss://fast.example"), emptyMap())
    private val all = RelayPublishResult(setOf("wss://fast.example"), mapOf("wss://dead.example" to "timeout"))

    private fun completion(warning: String?, finalResult: CompletableDeferred<RelayPublishResult>) = PostCompletion(
        eventId = "e", replyToId = null, noteContext = NoteContext.Timeline,
        publishResult = first, warning = warning, finalResult = finalResult,
    )

    @Test
    fun warningShowsAtOnceAndTheRelayResultFollowsWhenEveryRelayHasAnswered() = runTest {
        val host = SnackbarHostState()
        val final = CompletableDeferred<RelayPublishResult>()
        val job = launch { showPostCompletionSnackbars(host, completion("下書きを削除できませんでした", final)) }
        runCurrent()
        assertEquals("下書きを削除できませんでした", host.currentSnackbarData?.visuals?.message)

        final.complete(all)
        runCurrent()
        // 警告を消さずに、その後ろに並ぶ。
        assertEquals("下書きを削除できませんでした", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData!!.dismiss()
        runCurrent()
        val result = assertIs<PublishResultSnackbarVisuals>(host.currentSnackbarData?.visuals)
        assertEquals(listOf("wss://dead.example"), result.failedRelays)
        host.currentSnackbarData!!.dismiss()
        job.join()
    }

    @Test
    fun resultWaitsForEveryRelayWhenThereIsNoWarning() = runTest {
        val host = SnackbarHostState()
        val final = CompletableDeferred<RelayPublishResult>()
        val job = launch { showPostCompletionSnackbars(host, completion(null, final)) }
        runCurrent()
        assertNull(host.currentSnackbarData)
        final.complete(first)
        runCurrent()
        val result = assertIs<PublishResultSnackbarVisuals>(host.currentSnackbarData?.visuals)
        assertEquals(emptyList(), result.failedRelays)
        host.currentSnackbarData!!.dismiss()
        job.join()
    }

    @Test
    fun eachSnackbarKeepsItsOwnFailedRelays() {
        val a = PublishResultSnackbarVisuals(RelayPublishResult(emptySet(), mapOf("wss://a.example" to "x")))
        val b = PublishResultSnackbarVisuals(RelayPublishResult(emptySet(), mapOf("wss://b.example" to "x")))
        assertEquals(listOf("wss://a.example"), a.failedRelays)
        assertEquals(listOf("wss://b.example"), b.failedRelays)
    }
}
