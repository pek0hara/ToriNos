package com.nostr.torinos.ui.channel

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.*
import com.nostr.torinos.ui.timeline.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChannelPublicationServiceTest {
    private class Signer : AccountSigner {
        override val pubkey = "me"
        var signatures = 0
        override fun encryptToSelf(plaintext: String) = plaintext
        override fun decrypt(content: String, peerPubkey: String) = content
        override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?) =
            NostrEvent("event-${++signatures}", pubkey, createdAt ?: 1, kind, tags, content, "signature")
    }

    @Test fun allFailureThenRetryUsesTheSameSignedEvent() = runTest {
        val signer = Signer()
        val sent = mutableListOf<NostrEvent>()
        val publisher = SignedEventPublisher(signer, relayPublisher = { event, urls, callback ->
            sent += event
            val result = if (sent.size == 1) RelayPublishResult(emptySet(), urls.associateWith { "timeout" })
                else RelayPublishResult(urls.toSet(), emptyMap())
            callback(result)
            result
        })
        val service = ChannelPublicationService(backgroundScope, signer, publisher)
        suspend fun submit() = service.publish("dialog", "channel", 40, emptyList(), listOf("wss://relay.example"), backgroundScope)
        assertIs<SignedPublishResult.Failed>(submit())
        assertIs<SignedPublishResult.Published>(submit())
        assertEquals(1, signer.signatures)
        assertEquals(sent.first(), sent.last())
    }

    @Test fun duplicateSubmitSharesOneOperation() = runTest {
        val signer = Signer()
        var deliveries = 0
        val publisher = SignedEventPublisher(signer, relayPublisher = { _, urls, callback ->
            deliveries++
            delay(50)
            RelayPublishResult(urls.toSet(), emptyMap()).also { callback(it) }
        })
        val service = ChannelPublicationService(backgroundScope, signer, publisher)
        val first = async { service.publish("dialog", "channel", 40, emptyList(), listOf("wss://relay.example"), backgroundScope) }
        val second = async { service.publish("dialog", "channel", 40, emptyList(), listOf("wss://relay.example"), backgroundScope) }
        assertIs<SignedPublishResult.Published>(first.await())
        assertIs<SignedPublishResult.Published>(second.await())
        assertEquals(1, signer.signatures)
        assertEquals(1, deliveries)
    }

    @Test fun retryRevalidatesSourceBeforeSendingTheSavedEvent() = runTest {
        val signer = Signer()
        var deliveries = 0
        var currentSource = true
        val publisher = SignedEventPublisher(signer, relayPublisher = { _, urls, _ ->
            deliveries++
            RelayPublishResult(emptySet(), urls.associateWith { "timeout" })
        })
        val service = ChannelPublicationService(backgroundScope, signer, publisher)
        suspend fun submit() = service.publish("dialog", "channel", 41, emptyList(), listOf("wss://relay.example"), backgroundScope,
            validate = { check(currentSource) { "source changed" } })
        assertIs<SignedPublishResult.Failed>(submit())
        currentSource = false
        assertEquals("source changed", assertIs<SignedPublishResult.Failed>(submit()).cause.message)
        assertEquals(1, deliveries)
        assertEquals(1, signer.signatures)
    }

    @Test fun validationCancellationIsNotConvertedToPublishFailure() = runTest {
        val signer = Signer()
        val service = ChannelPublicationService(backgroundScope, signer)
        assertFailsWith<CancellationException> {
            service.publish("dialog", "channel", 40, emptyList(), listOf("wss://relay.example"), backgroundScope,
                validate = { throw CancellationException("cancelled") })
        }
        assertEquals(0, signer.signatures)
    }

    @Test fun staleFailureCannotFinishAnotherDeliveryAttempt() {
        val event = NostrEvent("id", "me", 1, 40, emptyList(), "", "sig")
        val ticket = PublishTicket(event, setOf("a", "b"))
        val first = ticket.beginAttempt().first
        ticket.record(first, RelayPublishResult(setOf("a"), mapOf("b" to "timeout")))
        val (second, remaining) = ticket.beginAttempt()
        assertEquals(setOf("b"), remaining)
        ticket.record(first, RelayPublishResult(emptySet(), mapOf("b" to "late failure")))
        assertEquals(setOf("b"), ticket.progress.value.pending)
        ticket.record(second, RelayPublishResult(setOf("b"), emptyMap()))
        assertEquals(setOf("a", "b"), ticket.progress.value.result.succeededRelays)
        assertTrue(ticket.progress.value.complete)
    }
}
