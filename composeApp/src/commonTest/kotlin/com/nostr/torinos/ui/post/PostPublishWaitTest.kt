package com.nostr.torinos.ui.post

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.AccountSessionResources
import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 投稿と下書きの保存は、応答しないリレーを待たずに、最初に受理したリレーで完了する。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PostPublishWaitTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun saveCompletesWhenTheDraftPublisherReturns() = runTest {
        val gate = CompletableDeferred<RelayPublishResult>()
        val sent = mutableListOf<NostrEvent>()
        val viewModel = PostViewModel(session(), publishDraft = { event -> sent += event; gate.await() })
        viewModel.onTextChange("下書き")
        var saved = false
        viewModel.saveMemo { saved = true }
        runCurrent()
        assertEquals(MEMO_EVENT_KIND, sent.single().kind)
        assertTrue(viewModel.state.value.isSavingMemo)

        gate.complete(RelayPublishResult(setOf("wss://fast.example"), emptyMap()))
        runCurrent()
        assertTrue(saved)
        assertFalse(viewModel.state.value.isSavingMemo)
    }

    @Test
    fun postCompletesOnFirstAcceptanceAndReportsTheRestLater() = runTest {
        var reportAll: (suspend (RelayPublishResult) -> Unit)? = null
        var sentTargets: Collection<String>? = listOf("unset")
        val viewModel = PostViewModel(session(), publishPost = { event, targets, onAllCompleted ->
            assertEquals(1, event.kind)
            sentTargets = targets
            reportAll = onAllCompleted
            RelayPublishResult(setOf("wss://fast.example"), emptyMap())
        })
        viewModel.onTextChange("こんにちは")
        viewModel.post()
        runCurrent()

        assertEquals(null, sentTargets)
        val completion = viewModel.state.value.completion!!
        assertFalse(viewModel.state.value.isPosting)
        assertFalse(completion.finalResult.isCompleted)

        val all = RelayPublishResult(setOf("wss://fast.example"), mapOf("wss://dead.example" to "timeout"))
        reportAll!!(all)
        assertEquals(all, completion.finalResult.await())
    }

    @Test
    fun postFailsWhenNoRelayAccepts() = runTest {
        val viewModel = PostViewModel(session(), publishPost = { _, _, _ ->
            RelayPublishResult(emptySet(), mapOf("wss://dead.example" to "timeout"))
        })
        viewModel.onTextChange("こんにちは")
        viewModel.post()
        runCurrent()
        assertEquals(null, viewModel.state.value.completion)
        assertEquals("こんにちは", viewModel.state.value.text)
        assertTrue(viewModel.state.value.error!!.contains("wss://dead.example"))
    }

    private fun session() = AccountSession(
        sessionId = "test",
        pubkey = FakeSigner.pubkey,
        signer = FakeSigner,
        resources = AccountSessionResources(),
    )

    private object FakeSigner : AccountSigner {
        override val pubkey: String = "2".repeat(64)
        override suspend fun nip44Encrypt(plaintext: String, peerPubkey: String): String = plaintext
        override suspend fun nip44Decrypt(payload: String, peerPubkey: String): String = payload
        override suspend fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?) =
            NostrEvent(
                id = "1".repeat(64), pubkey = pubkey, createdAt = createdAt ?: 1000L, kind = kind,
                tags = tags, content = content, sig = "3".repeat(128),
            )
    }
}
