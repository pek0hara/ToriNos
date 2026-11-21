package com.nostr.torinos.ui.timeline

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.engagement.NoteEngagementCommand
import com.nostr.torinos.engagement.NoteTarget
import com.nostr.torinos.engagement.RepostNotice
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 誤リポストに気づけるよう、リポストは全リレーの結果がそろったら知らせる。 */
class RepostDeliveryNoticeTest {
    private val first = RelayPublishResult(setOf("wss://fast.example"), emptyMap())
    private val all = RelayPublishResult(setOf("wss://fast.example"), mapOf("wss://dead.example" to "timeout"))
    private val note = NostrEvent(
        id = "a".repeat(64), pubkey = "b".repeat(64), createdAt = 1L, kind = 1,
        tags = emptyList(), content = "hello", sig = "c".repeat(128),
    )

    private fun coordinator(
        notices: MutableList<RepostNotice>,
        finalResult: RelayPublishResult = all,
    ) = NoteEngagementCoordinator(
        signer = FakeSigner,
        publisher = { _, onAllCompleted -> first.also { onAllCompleted(finalResult) } },
        relayHint = { "wss://fast.example" },
        onRepostDelivered = { notices += it },
    )

    @Test
    fun repostReportsTheResultOfEveryRelay() = runTest {
        val notices = mutableListOf<RepostNotice>()
        coordinator(notices).execute(NoteEngagementCommand.AddRepost(note)).getOrThrow()
        assertEquals(listOf(RepostNotice(FakeSigner.pubkey, all)), notices)
    }

    @Test
    fun otherEngagementIsNotReported() = runTest {
        val notices = mutableListOf<RepostNotice>()
        val coordinator = coordinator(notices)
        coordinator.execute(NoteEngagementCommand.AddLike(NoteTarget(note.id, note.pubkey))).getOrThrow()
        coordinator.execute(NoteEngagementCommand.RemoveRepost("d".repeat(64))).getOrThrow()
        assertTrue(notices.isEmpty())
    }

    @Test
    fun repostThatReachedNoRelayIsLeftToTheFailureMessage() = runTest {
        val notices = mutableListOf<RepostNotice>()
        coordinator(notices, finalResult = RelayPublishResult(emptySet(), mapOf("wss://dead.example" to "timeout")))
            .execute(NoteEngagementCommand.AddRepost(note))
        assertTrue(notices.isEmpty())
    }

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
