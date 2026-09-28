package com.nostr.torinos.ui.timeline

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NoteDeletionServiceTest {
    @Test
    fun deletesOwnedNoteWithNip09Tags(): Unit = runBlocking {
        val signer = FakeSigner("author")
        var published: NostrEvent? = null
        val publisher = SignedEventPublisher(signer) { event ->
            published = event
            RelayPublishResult(setOf("relay"), emptyMap())
        }
        val result = NoteDeletionService(signer, "session", publisher).delete(note(pubkey = "author"))

        assertIs<NoteDeletionResult.Deleted>(result)
        assertEquals(5, published?.kind)
        assertEquals(
            listOf(
                listOf("e", "note-id"),
                listOf("k", "1"),
                listOf("client", "ToriNos"),
            ),
            published?.tags,
        )
    }

    @Test
    fun rejectsNoteOwnedByAnotherAccount(): Unit = runBlocking {
        val signer = FakeSigner("me")
        var didPublish = false
        val publisher = SignedEventPublisher(signer) {
            didPublish = true
            RelayPublishResult(setOf("relay"), emptyMap())
        }

        val result = NoteDeletionService(signer, "session", publisher).delete(note(pubkey = "other"))

        assertIs<NoteDeletionResult.NotOwner>(result)
        assertEquals(false, didPublish)
    }

    private fun note(pubkey: String) = NostrEvent(
        id = "note-id",
        pubkey = pubkey,
        createdAt = 1,
        kind = 1,
        tags = emptyList(),
        content = "content",
        sig = "sig",
    )

    private class FakeSigner(override val pubkey: String) : AccountSigner {
        override fun encryptToSelf(plaintext: String) = plaintext
        override fun decrypt(content: String, peerPubkey: String) = content
        override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?) =
            NostrEvent("delete-id", pubkey, createdAt ?: 1, kind, tags, content, "sig")
    }
}
