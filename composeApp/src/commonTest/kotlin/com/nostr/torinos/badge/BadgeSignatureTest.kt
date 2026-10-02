package com.nostr.torinos.badge

import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BadgeSignatureTest {
    @Test fun genuineSignatureAcceptedButChangedPayloadRejectedBeforeDeduplication() = runTest {
        val event = signEvent(privateKeyHex = "0".repeat(63) + "1", kind = 10008, content = "", tags = emptyList(), createdAt = 1)
        val transport = BadgeTransport(open = { badgeResponse(it, listOf(event.copy(content = "tampered"), event)) }, validationDispatcher = StandardTestDispatcher(testScheduler))
        val result = transport.fetch(listOf(NostrFilter(kinds = listOf(10008))), setOf("wss://one.example"))
        assertEquals(listOf(event), result.events)
    }
    @Test fun signatureFromAnotherKeyCannotAuthorizeDisplayList() = runTest {
        val event = signEvent(privateKeyHex = "0".repeat(63) + "1", kind = 10008, content = "", tags = emptyList(), createdAt = 1)
        val transport = BadgeTransport(open = { badgeResponse(it, listOf(event.copy(pubkey = badgeOwner))) }, validationDispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(transport.fetch(listOf(NostrFilter(kinds = listOf(10008))), setOf("wss://one.example")).events.isEmpty())
    }
}
