package com.nostr.torinos.ui.settings

import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.emoji.PublishedEmojiSet
import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiReferenceRequestTest {
    private fun address(id: String) = EmojiSetAddress("c".repeat(64), id)
    private fun published(id: String) = PublishedEmojiSet(address(id), id, id, 1, listOf(CustomEmoji("x", "https://example.com/x.png")))

    @Test
    fun requestsUnknownAddressesByAdoptionCountWithinBudget() {
        val counts = mapOf(address("a") to 1, address("b") to 3, address("c") to 2, address("d") to 2)

        val requested = emojiReferencesToRequest(counts, published = listOf(published("b")), failed = emptySet(), budget = 3)

        assertEquals(listOf(address("c"), address("d")), requested)
    }

    @Test
    fun skipsAddressesTheRelayAlreadyReportedMissingSoTheyDoNotUseTheBudget() {
        val counts = mapOf(address("a") to 5, address("b") to 1)

        val requested = emojiReferencesToRequest(counts, published = emptyList(), failed = setOf(address("a")), budget = 1)

        assertEquals(listOf(address("b")), requested)
    }
}
