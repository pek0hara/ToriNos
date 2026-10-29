package com.nostr.torinos.ui.post

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 下書き一覧を開いたとき、同じリレーの取得を始め直さない。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftListLoadOnceTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val opened = mutableListOf<String>()
    private val viewModel = DraftListViewModel(DraftMemoRepository(
        signer = object : AccountSigner {
            override val pubkey = "aa".repeat(32)
            override suspend fun nip44Encrypt(plaintext: String, peerPubkey: String) = plaintext
            override suspend fun nip44Decrypt(payload: String, peerPubkey: String) = payload
            override suspend fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?): NostrEvent = error("unused")
        },
        openSession = { spec ->
            opened += (spec.target as RelayTarget.Single).url
            object : SubscriptionSession {
                override val id = spec.id
                override val signals = flow<SubscriptionSignal> { awaitCancellation() }
                override suspend fun update(filters: List<com.nostr.torinos.model.NostrFilter>, target: RelayTarget) = Unit
                override suspend fun close() = Unit
            }
        },
    ))

    @Test
    fun theSameRelayRequestedWhileLoadingIsNotFetchedAgain() = runTest {
        viewModel.load("wss://a.example")
        runCurrent()
        viewModel.load("wss://a.example")
        runCurrent()
        assertEquals(listOf("wss://a.example"), opened)
    }

    @Test
    fun anotherRelayOrAnExplicitReloadStillFetches() = runTest {
        viewModel.load("wss://a.example")
        runCurrent()
        viewModel.load("wss://b.example")
        runCurrent()
        viewModel.reload()
        runCurrent()
        assertEquals(listOf("wss://a.example", "wss://b.example", "wss://b.example"), opened)
    }
}
