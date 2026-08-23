package com.nostr.torinos.engagement

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteEngagementServiceTest {
    @Test
    fun addCustomEmojiSignsExpectedEventAndCallsOnSignedBeforePublish() = runBlocking {
        val calls = mutableListOf<String>()
        val signer = RecordingSigner()
        val service = NoteEngagementService(signer) { event ->
            calls += "publish:${event.id}"
            RelayPublishResult(setOf("wss://relay.example"), emptyMap())
        }

        val result = service.execute(
            NoteEngagementCommand.AddEmoji(
                NoteTarget("note-id", "author-pubkey"),
                ReactionOption.Custom("bird", "https://example.com/bird.png"),
            ),
            onSigned = { calls += "signed:${it.id}" },
        ).getOrThrow()

        assertEquals(7, result.kind)
        assertEquals(":bird:", result.content)
        assertEquals(
            listOf(
                listOf("e", "note-id"),
                listOf("p", "author-pubkey"),
                listOf("emoji", "bird", "https://example.com/bird.png"),
            ),
            result.tags,
        )
        assertEquals(listOf("signed:event-1", "publish:event-1"), calls)
    }

    @Test
    fun removeReactionCreatesDeletionEvent() = runBlocking {
        val service = NoteEngagementService(RecordingSigner()) {
            RelayPublishResult(setOf("relay"), emptyMap())
        }

        val event = service.execute(
            NoteEngagementCommand.RemoveReaction("reaction-id"),
        ).getOrThrow()

        assertEquals(5, event.kind)
        assertEquals("", event.content)
        assertEquals(listOf(listOf("e", "reaction-id")), event.tags)
    }

    @Test
    fun missingSignerAndBlankTargetFailWithoutPublishing() = runBlocking {
        var publishCount = 0
        val missingSigner = NoteEngagementService(null) {
            publishCount++
            RelayPublishResult(setOf("relay"), emptyMap())
        }
        assertTrue(
            missingSigner.execute(
                NoteEngagementCommand.AddLike(NoteTarget("note", "author")),
            ).isFailure,
        )

        val blankTarget = NoteEngagementService(RecordingSigner()) {
            publishCount++
            RelayPublishResult(setOf("relay"), emptyMap())
        }
        assertTrue(
            blankTarget.execute(
                NoteEngagementCommand.AddLike(NoteTarget("", "author")),
            ).isFailure,
        )
        assertEquals(0, publishCount)
    }

    @Test
    fun repostEmbedsTargetAndPublisherFailureIsReturned() = runBlocking {
        val target = NostrEvent(
            id = "note-id",
            pubkey = "author-pubkey",
            createdAt = 10L,
            kind = 1,
            tags = emptyList(),
            content = "hello",
            sig = "target-sig",
        )
        var signedRepost: NostrEvent? = null
        val successfulService = NoteEngagementService(RecordingSigner()) { event ->
            signedRepost = event
            RelayPublishResult(setOf("relay"), emptyMap())
        }

        successfulService.execute(NoteEngagementCommand.AddRepost(target)).getOrThrow()

        assertEquals(6, signedRepost?.kind)
        assertEquals(listOf(listOf("e", "note-id"), listOf("p", "author-pubkey")), signedRepost?.tags)
        assertTrue(signedRepost?.content?.contains("\"id\":\"note-id\"") == true)

        val failingService = NoteEngagementService(RecordingSigner()) {
            error("all relays failed")
        }
        assertTrue(
            failingService.execute(
                NoteEngagementCommand.AddLike(NoteTarget("note", "author")),
            ).isFailure,
        )

        val zeroSuccessService = NoteEngagementService(RecordingSigner()) {
            RelayPublishResult(emptySet(), mapOf("relay" to "rejected"))
        }
        assertTrue(
            zeroSuccessService.execute(
                NoteEngagementCommand.AddLike(NoteTarget("note", "author")),
            ).isFailure,
        )
    }

    private class RecordingSigner : AccountSigner {
        override val pubkey: String = "signer-pubkey"
        private var nextId = 0

        override fun encryptToSelf(plaintext: String): String = plaintext

        override fun decrypt(content: String, peerPubkey: String): String = content

        override fun sign(
            content: String,
            kind: Int,
            tags: List<List<String>>,
            createdAt: Long?,
        ): NostrEvent = NostrEvent(
            id = "event-${++nextId}",
            pubkey = pubkey,
            createdAt = createdAt ?: 1L,
            kind = kind,
            tags = tags,
            content = content,
            sig = "sig",
        )
    }
}
