package com.nostr.torinos.zap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BOLT11 仕様の例（Examples）を使う。 */
class Bolt11Test {
    private val paymentHash = "0001020304050607080900010203040506070809000102030405060708090102"

    private val anyAmount = "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"
    private val coffee = "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh"
    private val hashedDescription = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqs9qrsgq7ea976txfraylvgzuxs8kgcw23ezlrszfnh8r6qtfpr6cxga50aj6txm9rxrydzd06dfeawfk6swupvz4erwnyutnjq7x39ymw6j38gp7ynn44"

    @Test
    fun invoiceWithoutAmount() {
        val invoice = parseBolt11(anyAmount)!!
        assertTrue(invoice.isMainnet)
        assertNull(invoice.amountMsats)
        assertEquals(1496314658, invoice.timestamp)
        assertEquals(3600, invoice.expirySeconds)
        assertEquals(paymentHash, invoice.paymentHash)
        assertNull(invoice.descriptionHash)
    }

    @Test
    fun microUnitAndExpiry() {
        val invoice = parseBolt11(coffee)!!
        assertEquals(250_000_000L, invoice.amountMsats)
        assertEquals(60, invoice.expirySeconds)
        assertTrue(invoice.isExpired(1496314658 + 60))
        assertFalse(invoice.isExpired(1496314658 + 59))
    }

    @Test
    fun milliUnitAndDescriptionHash() {
        val invoice = parseBolt11(hashedDescription)!!
        assertEquals(2_000_000_000L, invoice.amountMsats)
        assertEquals("3925b6f67e2c340036ed12093dd44e0368df1b6ea26c53dbe4811f58fd5db8c1", invoice.descriptionHash)
    }

    @Test
    fun acceptsUppercaseAndLightningPrefix() {
        assertEquals(250_000_000L, parseBolt11("lightning:" + coffee.uppercase())?.amountMsats)
    }

    @Test
    fun rejectsBrokenInput() {
        assertNull(parseBolt11(coffee.dropLast(1) + "q"))
        assertNull(parseBolt11("not an invoice"))
        assertNull(parseBolt11(""))
    }

    @Test
    fun amountUnits() {
        fun amountOf(hrp: String) = parseBolt11(synthesizeInvoice(hrp))?.amountMsats
        assertEquals(100_000_000_000L, amountOf("lnbc1"))
        assertEquals(100_000L, amountOf("lnbc1u"))
        assertEquals(2_100L, amountOf("lnbc21n"))
        assertEquals(1L, amountOf("lnbc10p"))
        // pico の端数（msats より細かい額）は不正。
        assertNull(parseBolt11(synthesizeInvoice("lnbc11p")))
        assertNull(parseBolt11(synthesizeInvoice("lnbc01u")))
        assertNull(parseBolt11(synthesizeInvoice("lnbc999999999999999")))
    }

    @Test
    fun networks() {
        assertEquals("tb", parseBolt11(synthesizeInvoice("lntb10u"))?.network)
        assertEquals("bcrt", parseBolt11(synthesizeInvoice("lnbcrt10u"))?.network)
        assertFalse(parseBolt11(synthesizeInvoice("lntb10u"))!!.isMainnet)
        assertNull(parseBolt11(synthesizeInvoice("lnxx10u")))
    }
}
