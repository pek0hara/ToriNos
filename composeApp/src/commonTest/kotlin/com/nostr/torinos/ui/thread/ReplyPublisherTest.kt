package com.nostr.torinos.ui.thread

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReplyEventReference
import com.nostr.torinos.model.ReplyTarget
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
                target = timelineTarget(),
            ),
        )

        assertIs<ReplyPublishResult.Published>(result)
        assertEquals("reply", published?.content)
        assertEquals(COMMENT_EVENT_KIND, published?.kind)
        assertEquals(
            listOf(
                listOf("E", "root-id", "", "root-author"),
                listOf("K", "1"),
                listOf("P", "root-author"),
                listOf("e", "root-id", "", "root-author"),
                listOf("k", "1"),
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
        val command = ReplyCommand("reply", timelineTarget())

        assertIs<ReplyPublishResult.Failure.MissingSigner>(missingSigner.publish(command))
        assertEquals(0, publishCount)

        val rejected = ReplyPublisher(RecordingSigner()) {
            RelayPublishResult(emptySet(), mapOf("relay" to "rejected"))
        }
        assertIs<ReplyPublishResult.Failure.PublishFailed>(rejected.publish(command))
    }

    private fun timelineTarget(): ReplyTarget.Timeline {
        val root = ReplyEventReference("root-id", 1, "root-author")
        return ReplyTarget.Timeline(root = root, parent = root)
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
