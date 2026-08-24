package com.nostr.torinos.ui.thread

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReplyPublisherTest {
    @Test
    fun publishesATrimmedReplyWithContextTags(): Unit = runBlocking {
        var published: NostrEvent? = null
        val publisher = ReplyPublisher(RecordingSigner()) { event ->
            published = event
            RelayPublishResult(setOf("relay"), emptyMap())
        }

        val result = publisher.publish(
            ReplyCommand(
                content = "  reply  ",
                replyToId = "root-id",
                replyToPubkey = "root-author",
                noteContext = NoteContext.Timeline,
            ),
        )

        assertIs<ReplyPublishResult.Published>(result)
        assertEquals("reply", published?.content)
        assertEquals(1, published?.kind)
        assertEquals(
            listOf(
                listOf("e", "root-id"),
                listOf("p", "root-author"),
                listOf("client", "ToriNos"),
            ),
            published?.tags,
        )
    }

    @Test
    fun rejectsMissingSignerAndZeroSuccessfulRelays(): Unit = runBlocking {
        var publishCount = 0
        val missingSigner = ReplyPublisher(null) {
            publishCount++
            RelayPublishResult(setOf("relay"), emptyMap())
        }
        val command = ReplyCommand("reply", "root", "author", NoteContext.Timeline)

        assertIs<ReplyPublishResult.Failure.MissingSigner>(missingSigner.publish(command))
        assertEquals(0, publishCount)

        val rejected = ReplyPublisher(RecordingSigner()) {
            RelayPublishResult(emptySet(), mapOf("relay" to "rejected"))
        }
        assertIs<ReplyPublishResult.Failure.PublishFailed>(rejected.publish(command))
    }

    private class RecordingSigner : AccountSigner {
        override val pubkey: String = "signer"

        override fun encryptToSelf(plaintext: String): String = plaintext

        override fun decrypt(content: String, peerPubkey: String): String = content

        override fun sign(
            content: String,
            kind: Int,
            tags: List<List<String>>,
            createdAt: Long?,
        ): NostrEvent = NostrEvent(
            id = "reply-id",
            pubkey = pubkey,
            createdAt = createdAt ?: 1,
            kind = kind,
            tags = tags,
            content = content,
            sig = "sig",
        )
    }
}
