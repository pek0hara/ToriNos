package com.nostr.torinos.status

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.CustomEmoji
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusEventCodecTest {
    @Test
    fun parse_defaultsMissingOrEmptyIdentifierToGeneral() {
        assertEquals(GENERAL_STATUS_IDENTIFIER, event(tags = emptyList()).parsed()?.identifier)
        assertEquals(
            GENERAL_STATUS_IDENTIFIER,
            event(tags = listOf(listOf("d", ""))).parsed()?.identifier,
        )
    }

    @Test
    fun parse_preservesIdentifierAndContentAndNormalizesReferences() {
        val parsed = event(
            content = "  working  ",
            tags = listOf(
                listOf("d", "General"),
                listOf("expiration", "1234"),
                listOf("r", " https://example.com/a "),
                listOf("r", "https://example.com/a"),
                listOf("r", ""),
                listOf("emoji", "bird", "https://example.com/bird.png"),
            ),
        ).parsed()

        assertEquals("General", parsed?.identifier)
        assertEquals("  working  ", parsed?.content)
        assertEquals(1234L, parsed?.expiration)
        assertEquals(listOf("https://example.com/a"), parsed?.referenceUrls)
        assertEquals(mapOf("bird" to "https://example.com/bird.png"), parsed?.customEmojis)
    }

    @Test
    fun parse_rejectsOtherKindAndTreatsInvalidExpirationAsAbsent() {
        assertNull(event(kind = 1).parsed())
        assertNull(event(tags = listOf(listOf("expiration", "invalid"))).parsed()?.expiration)
    }

    @Test
    fun buildTags_deduplicatesExplicitAndContentUrlsAndAddsUsedEmoji() {
        val tags = StatusEventCodec.buildTags(
            identifier = " custom ",
            content = "See https://example.com/a and :bird:",
            expiration = 2000L,
            explicitReferenceUrl = " https://example.com/a ",
            customEmojis = listOf(CustomEmoji("bird", "https://example.com/bird.png")),
        )

        assertEquals(listOf("d", "custom"), tags[0])
        assertEquals(listOf("expiration", "2000"), tags[1])
        assertEquals(1, tags.count { it.firstOrNull() == "r" })
        assertEquals(
            listOf("emoji", "bird", "https://example.com/bird.png"),
            tags.first { it.firstOrNull() == "emoji" },
        )
    }

    private fun NostrEvent.parsed(): StatusEntry? = StatusEventCodec.parse(this)

    private fun event(
        kind: Int = STATUS_EVENT_KIND,
        content: String = "status",
        tags: List<List<String>> = listOf(listOf("d", GENERAL_STATUS_IDENTIFIER)),
    ) = NostrEvent(
        id = "1".repeat(64),
        pubkey = "2".repeat(64),
        createdAt = 1000L,
        kind = kind,
        tags = tags,
        content = content,
        sig = "3".repeat(128),
    )
}
