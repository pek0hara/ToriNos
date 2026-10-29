package com.nostr.torinos.zap

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZapRequestTest {
    private val endpoint = PayEndpoint(
        url = "https://example.com/.well-known/lnurlp/alice",
        callback = "https://example.com/cb",
        minSendableMsats = 1_000,
        maxSendableMsats = 1_000_000_000,
        commentAllowed = 10,
        nostrPubkey = "c".repeat(64),
    )

    @Test
    fun tagsFollowAppendixA() {
        val target = ZapTarget(noteId, authorPubkey, kind = 42)
        val tags = zapRequestTags(target, 21_000, "lnurl1abc", listOf("wss://a", "wss://b"))
        assertEquals(
            listOf(
                listOf("relays", "wss://a", "wss://b"),
                listOf("amount", "21000"),
                listOf("lnurl", "lnurl1abc"),
                listOf("p", authorPubkey),
                listOf("e", noteId),
                listOf("k", "42"),
            ),
            tags,
        )
    }

    @Test
    fun relaysPutOwnFirstDeduplicateAndCap() {
        val own = listOf("wss://own1/", "wss://own2", "ws://insecure", "https://web")
        val author = (1..20).map { "wss://author$it" } + "wss://own1"
        val relays = zapRelays(own, listOf("wss://channel"), author)
        assertEquals(listOf("wss://own1", "wss://own2", "wss://channel"), relays.take(3))
        assertEquals(MAX_ZAP_RELAYS, relays.size)
        assertEquals(relays.distinct(), relays)
    }

    @Test
    fun callbackEncodesWholeEventAndKeepsExistingQuery() {
        val json = """{"content":"a&b=c#d 🌸","tags":[["e","x"]]}"""
        val url = buildCallbackUrl(endpoint.copy(callback = "https://example.com/cb?user=alice"), 21_000, json, "hello & bye!")
        val parsed = Url(url)
        assertEquals("alice", parsed.parameters["user"])
        assertEquals("21000", parsed.parameters["amount"])
        assertEquals(json, parsed.parameters["nostr"])
        assertEquals(endpoint.lnurl, parsed.parameters["lnurl"])
        // commentAllowed で切り詰める。
        assertEquals("hello & by", parsed.parameters["comment"])
        assertTrue('#' !in url.substringAfter("?"))
    }

    @Test
    fun plainPaymentOmitsNostrAndComment() {
        val parsed = Url(buildCallbackUrl(endpoint.copy(commentAllowed = 0, nostrPubkey = null), 1_000, null, "hi"))
        assertNull(parsed.parameters["nostr"])
        assertNull(parsed.parameters["comment"])
    }

    @Test
    fun overlongUrlIsRejected() {
        assertFailsWith<ZapException> { buildCallbackUrl(endpoint, 1_000, "x".repeat(MAX_CALLBACK_URL_LENGTH), "") }
    }

    @Test
    fun redactsZapRequest() {
        assertEquals("https://e/cb?amount=1&nostr=…", redactCallbackUrl("https://e/cb?amount=1&nostr=%7B%22a"))
        assertEquals("https://e/cb?amount=1", redactCallbackUrl("https://e/cb?amount=1"))
    }

    @Test
    fun targetFromEventDetectsSplit() {
        val event = com.nostr.torinos.model.NostrEvent(noteId, authorPubkey, 1, 1,
            listOf(listOf("zap", "c".repeat(64), "wss://r", "1")), "", "")
        assertTrue(ZapTarget.of(event).hasZapSplit)
    }
}
