package com.nostr.torinos.zap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ZapReceiptTest {
    private val zapper = pubkeyOf(zapperKey)
    private val sender = pubkeyOf(senderKey)

    @Test
    fun validReceiptIsRead() {
        val receipt = parseZapReceipt(zapReceipt(zapRequest(content = "ありがとう")))
        assertNotNull(receipt)
        assertEquals(zapper, receipt.zapperPubkey)
        assertEquals(sender, receipt.senderPubkey)
        assertEquals(authorPubkey, receipt.recipientPubkey)
        assertEquals(noteId, receipt.targetEventId)
        assertEquals(21_000, receipt.amountMsats)
        assertEquals("ありがとう", receipt.comment)
    }

    @Test
    fun amountTagIsOptionalButMustMatch() {
        assertEquals(21_000, parseZapReceipt(zapReceipt(zapRequest(amountMsats = null)))?.amountMsats)
        assertNull(parseZapReceipt(zapReceipt(zapRequest(amountMsats = 22_000))))
    }

    @Test
    fun invoiceWithoutAmountIsDropped() {
        assertNull(parseZapReceipt(zapReceipt(bolt11 = synthesizeInvoice("lnbc"))))
        assertNull(parseZapReceipt(zapReceipt(bolt11 = "garbage")))
    }

    @Test
    fun mismatchedTagsAreDropped() {
        assertNull(parseZapReceipt(zapReceipt(recipient = "d".repeat(64))))
        assertNull(parseZapReceipt(zapReceipt(eventId = "d".repeat(64))))
        assertNull(parseZapReceipt(zapReceipt(senderTag = "d".repeat(64))))
        assertNull(parseZapReceipt(zapReceipt(zapRequest(extraTags = listOf(listOf("p", "d".repeat(64)))))))
        assertNull(parseZapReceipt(zapReceipt(zapRequest(extraTags = listOf(listOf("e", "d".repeat(64)))))))
        // P タグは任意。
        assertNotNull(parseZapReceipt(zapReceipt(senderTag = null)))
    }

    @Test
    fun brokenSignaturesAreDropped() {
        val request = zapRequest()
        assertNull(parseZapReceipt(zapReceipt(request.copy(content = "tampered"))))
        val receipt = zapReceipt()
        assertNull(parseZapReceipt(receipt.copy(createdAt = receipt.createdAt + 1)))
    }

    @Test
    fun wrongKindsAreDropped() {
        assertNull(parseZapReceipt(zapRequest()))
        val notRequest = com.nostr.torinos.crypto.signEvent(senderKey, "", kind = 1,
            tags = listOf(listOf("p", authorPubkey), listOf("e", noteId)), createdAt = 1)
        assertNull(parseZapReceipt(zapReceipt(notRequest)))
    }

    @Test
    fun summaryCountsOnlyTheRecipientsZapperAndDeduplicates() {
        val first = zapReceipt(zapRequest(amountMsats = 21_000))
        val duplicate = zapReceipt(zapRequest(amountMsats = 21_000), createdAt = 1_700_000_020)
        val second = zapReceipt(zapRequest(amountMsats = 1_000_000, key = otherKey), bolt11 = invoiceForMsats(1_000_000))
        val forged = zapReceipt(zapRequest(amountMsats = 5_000_000, key = otherKey, content = "x"),
            bolt11 = invoiceForMsats(5_000_000), key = otherKey)
        val receipts = listOf(first, duplicate, second, forged).mapNotNull { parseZapReceipt(it) }
        assertEquals(4, receipts.size)

        val total = summarizeZaps(receipts, noteId, authorPubkey, zapper)
        assertEquals(1_021_000, total.totalMsats)
        assertEquals(2, total.count)
        assertEquals(setOf(sender, pubkeyOf(otherKey)), total.senders)
        assertEquals(ZapTotal.Empty, summarizeZaps(receipts, noteId, authorPubkey, zapperPubkey = null))
        assertEquals(ZapTotal.Empty, summarizeZaps(receipts, "e".repeat(64), authorPubkey, zapper))
        assertEquals(ZapTotal.Empty, summarizeZaps(receipts, noteId, "d".repeat(64), zapper))
    }

    @Test
    fun amountFormat() {
        assertEquals("", formatZapAmount(0))
        assertEquals("", formatZapAmount(999))
        assertEquals("1", formatZapAmount(1_999))
        assertEquals("2100", formatZapAmount(2_100_000))
        assertEquals("9999", formatZapAmount(9_999_999))
        assertEquals("10k", formatZapAmount(10_000_000))
        assertEquals("12k", formatZapAmount(12_499_000))
        assertEquals("13k", formatZapAmount(12_500_000))
        // 切り捨ててから丸める。
        assertEquals("12k", formatZapAmount(12_499_999))
        assertEquals("999k", formatZapAmount(999_499_000))
        assertEquals("1M", formatZapAmount(999_500_000))
        assertEquals("1.2M", formatZapAmount(1_234_000_000))
        assertEquals("2M", formatZapAmount(1_960_000_000))
        assertEquals("123M", formatZapAmount(123_456_000_000))
    }

    @Test
    fun invoiceChecksBeforePaying() {
        val now = 1_700_000_100L
        assertEquals(21_000, checkInvoice(invoiceForMsats(21_000), 21_000, now).amountMsats)
        assertFailsWith<ZapException> { checkInvoice(invoiceForMsats(22_000), 21_000, now) }
        assertFailsWith<ZapException> { checkInvoice(synthesizeInvoice("lnbc"), 21_000, now) }
        assertFailsWith<ZapException> { checkInvoice(synthesizeInvoice("lntb210n"), 21_000, now) }
        assertFailsWith<ZapException> { checkInvoice(invoiceForMsats(21_000, expiry = 60), 21_000, now) }
        assertFailsWith<ZapException> { checkInvoice("garbage", 21_000, now) }
    }
}
