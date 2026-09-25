package com.nostr.torinos.status

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StatusPublisherTest {
    @Test
    fun missingSignerAndEmptyRegularContentAreRejectedWithoutSending() = runTest {
        var sends = 0
        val withoutSigner = StatusPublisher(null, customEmojis = { emptyList() }) { _, _ -> sends++ }
        assertIs<StatusPublishResult.Rejected>(
            withoutSigner.publish(command("text"), StatusPublishTarget.WritableRelays),
        )

        val withSigner = StatusPublisher(FakeSigner, customEmojis = { emptyList() }) { _, _ -> sends++ }
        assertIs<StatusPublishResult.Rejected>(
            withSigner.publish(command("   "), StatusPublishTarget.WritableRelays),
        )
        assertEquals(0, sends)
    }

    @Test
    fun deletionAllowsEmptyContentAndSelectedRelayIsForwarded() = runTest {
        var sentEvent: NostrEvent? = null
        var sentTarget: StatusPublishTarget? = null
        val publisher = StatusPublisher(FakeSigner, customEmojis = { emptyList() }) { event, target ->
            sentEvent = event
            sentTarget = target
        }

        val result = publisher.publish(
            command("", isDeletion = true),
            StatusPublishTarget.SelectedRelay("wss://relay.example"),
        )

        assertIs<StatusPublishResult.Published>(result)
        assertEquals("", sentEvent?.content)
        assertEquals(StatusPublishTarget.SelectedRelay("wss://relay.example"), sentTarget)
    }

    @Test
    fun sendFailureDoesNotReturnPublishedEvent() = runTest {
        val publisher = StatusPublisher(FakeSigner, customEmojis = { emptyList() }) { _, _ ->
            error("offline")
        }

        val result = publisher.publish(command("status"), StatusPublishTarget.WritableRelays)

        assertIs<StatusPublishResult.Rejected>(result)
        assertTrue(result.message.contains("offline"))
    }

    private fun command(content: String, isDeletion: Boolean = false) = PublishStatusCommand(
        identifier = GENERAL_STATUS_IDENTIFIER,
        content = content,
        expiration = null,
        referenceUrl = null,
        isDeletion = isDeletion,
    )

    private object FakeSigner : AccountSigner {
        override val pubkey: String = "2".repeat(64)
        override fun encryptToSelf(plaintext: String): String = plaintext
        override fun decrypt(content: String, peerPubkey: String): String = content

        override fun sign(
            content: String,
            kind: Int,
            tags: List<List<String>>,
            createdAt: Long?,
        ) = NostrEvent(
            id = "1".repeat(64),
            pubkey = pubkey,
            createdAt = createdAt ?: 1000L,
            kind = kind,
            tags = tags,
            content = content,
            sig = "3".repeat(128),
        )
    }
}
