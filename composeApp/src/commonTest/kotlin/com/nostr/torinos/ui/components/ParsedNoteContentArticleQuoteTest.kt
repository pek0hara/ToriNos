package com.nostr.torinos.ui.components

import com.nostr.torinos.model.*

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.encodeNaddr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParsedNoteContentArticleQuoteTest {
    private val author = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

    @Test
    fun articleNaddrBecomesAQuoteAndLeavesTheText() {
        val naddr = encodeNaddr(30023, author, "torinos-abc", listOf("wss://yabu.me"))
        val parsed = parseNoteContent(note("よかった\n\nnostr:$naddr"))

        assertEquals("よかった", parsed.textContent)
        assertEquals(listOf("30023:$author:torinos-abc"), parsed.articleQuotes.map { it.address })
        assertEquals(listOf("wss://yabu.me"), parsed.articleQuotes.single().relayUrls)
    }

    @Test
    fun otherAddressableKindsStayAsText() {
        // 絵文字セット（kind 30030）は記事カードにしないので、本文に残す。
        val naddr = encodeNaddr(30030, author, "emoji")
        val parsed = parseNoteContent(note("nostr:$naddr"))

        assertTrue(parsed.articleQuotes.isEmpty())
        assertEquals("nostr:$naddr", parsed.textContent)
    }

    private fun note(content: String) = NostrEvent(
        id = "1".repeat(64),
        pubkey = author,
        createdAt = 0,
        kind = 1,
        tags = emptyList(),
        content = content,
        sig = "sig",
    )
}
