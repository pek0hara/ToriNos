package com.nostr.torinos.zap

import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LightningAddressTest {
    @Test
    fun lud16BecomesWellKnownUrl() {
        assertEquals("https://example.com/.well-known/lnurlp/alice", lud16ToUrl("Alice@Example.com"))
        assertEquals("https://getalby.com/.well-known/lnurlp/a.b-c_d+e", lud16ToUrl(" a.b-c_d+e@getalby.com "))
    }

    @Test
    fun invalidLud16IsRejected() {
        listOf("", "alice", "@example.com", "alice@", "a@b@example.com", "al ice@example.com",
            "alice@localhost", "alice@exa_mple.com", "alice@xyz.onion", "a/../b@example.com",
            "alice@192.168.1.1", "alice@127.0.0.1",
        ).forEach { assertNull(lud16ToUrl(it), it) }
    }

    @Test
    fun lud06RoundTripsLongUrls() {
        val url = "https://service.example.com/api/v1/lnurl/pay/" + "x".repeat(120)
        val lnurl = encodeLnurl(url)
        assertTrue(lnurl.length > 90)
        assertEquals(url, decodeLnurl(lnurl))
        assertEquals(url, decodeLnurl(lnurl.uppercase()))
        assertEquals(url, decodeLnurl("lightning:$lnurl"))
    }

    @Test
    fun lud06MustBeHttps() {
        assertNull(decodeLnurl(encodeLnurl("http://example.com/pay")))
        assertNull(decodeLnurl("lnurl1broken"))
        assertNull(decodeLnurl(com.nostr.torinos.crypto.Bech32.encode("npub", "https://a.example".encodeToByteArray())))
    }

    @Test
    fun profilePrefersLud16() {
        val lud06 = encodeLnurl("https://other.example/pay")
        assertEquals("https://example.com/.well-known/lnurlp/bob",
            NostrProfile(lud16 = "bob@example.com", lud06 = lud06).zapRecipientAddress()?.url)
        assertEquals("https://other.example/pay", NostrProfile(lud16 = "broken", lud06 = lud06).zapRecipientAddress()?.url)
        assertNull(NostrProfile().zapRecipientAddress())
    }

    @Test
    fun profileJsonReadsLightningFields() {
        val event = com.nostr.torinos.model.NostrEvent("i", "p", 0, 0, emptyList(),
            """{"name":"a","lud16":"a@example.com","lud06":"lnurl1x"}""", "s")
        val profile = event.toProfile()!!
        assertEquals("a@example.com", profile.lud16)
        assertEquals("lnurl1x", profile.lud06)
    }
}

class LnurlPayTest {
    private val url = "https://example.com/.well-known/lnurlp/alice"
    private val pubkey = "c".repeat(64)

    private fun body(extra: String = "", min: String = "1000", max: String = "100000000000", tag: String = "payRequest") =
        """{"tag":"$tag","callback":"https://example.com/cb","minSendable":$min,"maxSendable":$max,"metadata":"[]"$extra}"""

    @Test
    fun readsZapEndpoint() {
        val endpoint = parsePayEndpoint(url, body(""","allowsNostr":true,"nostrPubkey":"$pubkey","commentAllowed":140"""))
        assertEquals("https://example.com/cb", endpoint.callback)
        assertEquals(1000, endpoint.minSendableMsats)
        assertEquals(100_000_000_000L, endpoint.maxSendableMsats)
        assertEquals(140, endpoint.commentAllowed)
        assertEquals(pubkey, endpoint.nostrPubkey)
        assertTrue(endpoint.allowsNostr)
        assertTrue(endpoint.accepts(21_000))
        assertFalse(endpoint.accepts(999))
        assertEquals(url, decodeLnurl(endpoint.lnurl))
    }

    @Test
    fun withoutNostrIsPlainPayment() {
        assertFalse(parsePayEndpoint(url, body()).allowsNostr)
        assertFalse(parsePayEndpoint(url, body(""","allowsNostr":false,"nostrPubkey":"$pubkey"""")).allowsNostr)
        assertFalse(parsePayEndpoint(url, body(""","allowsNostr":true,"nostrPubkey":"XYZ"""")).allowsNostr)
        assertFalse(parsePayEndpoint(url, body(""","allowsNostr":true,"nostrPubkey":"${pubkey.uppercase()}"""")).allowsNostr)
    }

    @Test
    fun invalidEndpointsFail() {
        listOf(
            body(tag = "withdrawRequest"),
            body(min = "0"),
            body(min = "2000", max = "1000"),
            body(min = "\"abc\""),
            """{"tag":"payRequest","callback":"http://example.com/cb","minSendable":1,"maxSendable":2}""",
            "not json",
            "[]",
        ).forEach { assertFailsWith<ZapException>(it) { parsePayEndpoint(url, it) } }
    }

    @Test
    fun errorStatusShowsReason() {
        val error = assertFailsWith<ZapException> { parsePayEndpoint(url, """{"status":"ERROR","reason":"user not found"}""") }
        assertTrue(error.message!!.contains("user not found"))
    }

    @Test
    fun invoiceResponse() {
        assertEquals("lnbc1", parseInvoiceResponse("""{"pr":"lnbc1","routes":[]}"""))
        assertFailsWith<ZapException> { parseInvoiceResponse("""{"routes":[]}""") }
        assertFailsWith<ZapException> { parseInvoiceResponse("""{"status":"ERROR","reason":"too small"}""") }
    }
}
