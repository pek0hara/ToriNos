package com.nostr.torinos.ui.timeline

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SignedEventPublisherTest {
    @Test
    fun distinguishesMissingSignerSuccessAndRelayFailure(): Unit = runBlocking {
        assertIs<SignedPublishResult.MissingSigner>(
            SignedEventPublisher(null).publish("content", 1, emptyList()),
        )

        val success = SignedEventPublisher(FakeSigner()) {
            RelayPublishResult(setOf("relay"), emptyMap())
        }.publish("content", 42, listOf(listOf("client", "ToriNos")))
        val event = assertIs<SignedPublishResult.Published>(success).event
        assertEquals(42, event.kind)
        assertEquals("content", event.content)

        val failure = SignedEventPublisher(FakeSigner()) {
            RelayPublishResult(emptySet(), mapOf("relay" to "rejected"))
        }.publish("content", 1, emptyList())
        assertIs<SignedPublishResult.Failed>(failure)
    }

    private class FakeSigner : AccountSigner {
        override val pubkey = "pubkey"
        override fun encryptToSelf(plaintext: String) = plaintext
        override fun decrypt(content: String, peerPubkey: String) = content
        override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?) =
            NostrEvent("id", pubkey, createdAt ?: 1, kind, tags, content, "sig")
    }
}
