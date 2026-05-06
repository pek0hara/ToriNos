package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NostrUriTest {

    private val zeroId = "0".repeat(64)
    private val zeroBytes = ByteArray(32)

    // ---- extractNostrEventReferences ----

    @Test
    fun extract_noteUri_returnsEventId() {
        val noteId = "a".repeat(64)
        val noteBytes = ByteArray(32) { 0xAA.toByte() }
        val note1 = com.nostr.torinos.crypto.Bech32.encode("note", noteBytes)
        val refs = extractNostrEventReferences("nostr:$note1")
        assertEquals(1, refs.size)
        assertEquals(noteId, refs[0].eventId)
    }

    @Test
    fun extract_multipleUris_returnsAll() {
        val bytes1 = ByteArray(32) { 0x01 }
        val bytes2 = ByteArray(32) { 0x02 }
        val note1 = com.nostr.torinos.crypto.Bech32.encode("note", bytes1)
        val note2 = com.nostr.torinos.crypto.Bech32.encode("note", bytes2)
        val text = "hello nostr:$note1 world nostr:$note2"
        val refs = extractNostrEventReferences(text)
        assertEquals(2, refs.size)
    }

    @Test
    fun extract_duplicateUri_deduplicates() {
        val note1 = com.nostr.torinos.crypto.Bech32.encode("note", zeroBytes)
        val text = "nostr:$note1 nostr:$note1"
        val refs = extractNostrEventReferences(text)
        assertEquals(1, refs.size)
    }

    @Test
    fun extract_noUri_returnsEmpty() {
        val refs = extractNostrEventReferences("plain text with no nostr uris")
        assertTrue(refs.isEmpty())
    }

    @Test
    fun extract_unknownHrp_returnsEmpty() {
        val encoded = com.nostr.torinos.crypto.Bech32.encode("naddr", zeroBytes)
        val refs = extractNostrEventReferences("nostr:$encoded")
        assertTrue(refs.isEmpty())
    }

    // ---- stripNostrEventUris ----

    @Test
    fun strip_removesNoteUri() {
        val note1 = com.nostr.torinos.crypto.Bech32.encode("note", zeroBytes)
        val result = stripNostrEventUris("hello nostr:$note1 world")
        assertTrue("nostr:" !in result)
        assertTrue(result.contains("hello") && result.contains("world"))
    }

    @Test
    fun strip_leavesUnknownHrpIntact() {
        val encoded = com.nostr.torinos.crypto.Bech32.encode("naddr", zeroBytes)
        val text = "nostr:$encoded"
        val result = stripNostrEventUris(text)
        assertTrue(result.contains("nostr:"))
    }

    // ---- decodeNostrEventReference ----

    @Test
    fun decode_noteHrp_returnsEventId() {
        val note1 = com.nostr.torinos.crypto.Bech32.encode("note", zeroBytes)
        val ref = decodeNostrEventReference(note1)
        assertEquals(zeroId, ref?.eventId)
        assertTrue(ref!!.relayUrls.isEmpty())
        assertNull(ref.authorPubkey)
    }

    @Test
    fun decode_neventWithEventId_returnsEventId() {
        // TLV: type=0, length=32, value=32 zero bytes
        val tlv = byteArrayOf(0x00, 0x20) + zeroBytes
        val nevent = com.nostr.torinos.crypto.Bech32.encode("nevent", tlv)
        val ref = decodeNostrEventReference(nevent)
        assertEquals(zeroId, ref?.eventId)
    }

    @Test
    fun decode_neventWithRelay_returnsRelayUrl() {
        val relay = "wss://relay.example.com"
        val relayBytes = relay.encodeToByteArray()
        // type=0 (eventId), type=1 (relay)
        val tlv = byteArrayOf(0x00, 0x20) + zeroBytes +
                byteArrayOf(0x01, relayBytes.size.toByte()) + relayBytes
        val nevent = com.nostr.torinos.crypto.Bech32.encode("nevent", tlv)
        val ref = decodeNostrEventReference(nevent)
        assertEquals(listOf(relay), ref?.relayUrls)
    }

    @Test
    fun decode_neventWithAuthor_returnsAuthorPubkey() {
        val authorBytes = ByteArray(32) { 0xFF.toByte() }
        val tlv = byteArrayOf(0x00, 0x20) + zeroBytes +
                byteArrayOf(0x02, 0x20) + authorBytes
        val nevent = com.nostr.torinos.crypto.Bech32.encode("nevent", tlv)
        val ref = decodeNostrEventReference(nevent)
        assertEquals("ff".repeat(32), ref?.authorPubkey)
    }

    @Test
    fun encodeNevent_withAuthor_returnsDecodableNevent() {
        val authorPubkey = "f".repeat(64)
        val nevent = encodeNevent(eventId = zeroId, authorPubkey = authorPubkey)
        assertTrue(nevent.startsWith("nevent1"))

        val ref = decodeNostrEventReference(nevent)
        assertEquals(zeroId, ref?.eventId)
        assertEquals(authorPubkey, ref?.authorPubkey)
    }

    @Test
    fun decode_unknownHrp_returnsNull() {
        val encoded = com.nostr.torinos.crypto.Bech32.encode("npub", zeroBytes)
        assertNull(decodeNostrEventReference(encoded))
    }

    @Test
    fun decodeNpub_validNpub_returnsPubkey() {
        val npub = com.nostr.torinos.crypto.Bech32.encode("npub", zeroBytes)
        assertEquals(zeroId, decodeNpub(npub))
    }

    @Test
    fun decodeNpub_invalidHrp_returnsNull() {
        val note = com.nostr.torinos.crypto.Bech32.encode("note", zeroBytes)
        assertNull(decodeNpub(note))
    }

    @Test
    fun extractNpubReferences_validNpub_returnsProfileReference() {
        val npub = com.nostr.torinos.crypto.Bech32.encode("npub", zeroBytes)
        val refs = extractNpubReferences("hello $npub")
        assertEquals(1, refs.size)
        assertEquals(npub, refs[0].npub)
        assertEquals(zeroId, refs[0].pubkey)
    }

    @Test
    fun decode_invalidBech32_returnsNull() {
        assertNull(decodeNostrEventReference("not-bech32-at-all"))
    }

    // ---- quotedEventIds ----

    @Test
    fun quotedEventIds_fromQTag_returnsId() {
        val event = makeEvent(
            tags = listOf(listOf("q", "aabbcc")),
            content = "",
        )
        assertEquals(listOf("aabbcc"), quotedEventIds(event))
    }

    @Test
    fun quotedEventIds_fromUriInContent_returnsId() {
        val note1 = com.nostr.torinos.crypto.Bech32.encode("note", zeroBytes)
        val event = makeEvent(tags = emptyList(), content = "see nostr:$note1")
        assertEquals(listOf(zeroId), quotedEventIds(event))
    }

    @Test
    fun quotedEventIds_deduplicatesAcrossSources() {
        val note1 = com.nostr.torinos.crypto.Bech32.encode("note", zeroBytes)
        val event = makeEvent(
            tags = listOf(listOf("q", zeroId)),
            content = "nostr:$note1",
        )
        assertEquals(1, quotedEventIds(event).size)
    }

    // ---- helpers ----

    private fun makeEvent(tags: List<List<String>>, content: String) = NostrEvent(
        id = zeroId,
        pubkey = zeroId,
        createdAt = 0L,
        kind = 1,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )
}
