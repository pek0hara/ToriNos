package com.nostr.torinos.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Bech32Test {

    private val zeros32Hex = "0".repeat(64)
    private val zeros32 = ByteArray(32)

    // --- decode (using encode to produce valid inputs) ---

    @Test
    fun decode_npub_roundTrips() {
        val encoded = Bech32.encode("npub", zeros32)
        val (hrp, bytes) = Bech32.decode(encoded)
        assertEquals("npub", hrp)
        assertEquals(zeros32Hex, bytes.toHex())
    }

    @Test
    fun decode_nsec_roundTrips() {
        val encoded = Bech32.encode("nsec", zeros32)
        val (hrp, bytes) = Bech32.decode(encoded)
        assertEquals("nsec", hrp)
        assertEquals(zeros32Hex, bytes.toHex())
    }

    @Test
    fun decode_isCaseInsensitive() {
        val lower = Bech32.encode("npub", zeros32)
        val upper = lower.uppercase()
        val (hrpL, bytesL) = Bech32.decode(lower)
        val (hrpU, bytesU) = Bech32.decode(upper)
        assertEquals(hrpL, hrpU)
        assertEquals(bytesL.toHex(), bytesU.toHex())
    }

    @Test
    fun decode_throwsWhenNoSeparator() {
        assertFailsWith<IllegalArgumentException> {
            Bech32.decode("npubnoseparatorhere")
        }
    }

    @Test
    fun decode_throwsOnInvalidChecksum() {
        val valid = Bech32.encode("npub", zeros32)
        val corrupt = valid.dropLast(1) + if (valid.last() == 'q') 'p' else 'q'
        assertFailsWith<IllegalArgumentException> {
            Bech32.decode(corrupt)
        }
    }

    @Test
    fun decode_throwsOnInvalidCharacter() {
        // 'b' is not in the bech32 charset (charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l")
        assertFailsWith<IllegalArgumentException> {
            Bech32.decode("npub1" + "b".repeat(58))
        }
    }

    // --- encode ---

    @Test
    fun encode_startsWithHrp() {
        val encoded = Bech32.encode("npub", zeros32)
        assertTrue(encoded.startsWith("npub1"))
    }

    @Test
    fun encode_allZeroBytes_roundTrips() {
        val encoded = Bech32.encode("nsec", zeros32)
        val (hrp, bytes) = Bech32.decode(encoded)
        assertEquals("nsec", hrp)
        assertEquals(zeros32Hex, bytes.toHex())
    }

    @Test
    fun encode_allFfBytes_roundTrips() {
        val ffs = ByteArray(32) { 0xFF.toByte() }
        val encoded = Bech32.encode("npub", ffs)
        val (_, bytes) = Bech32.decode(encoded)
        assertEquals(ffs.toHex(), bytes.toHex())
    }

    @Test
    fun encode_sequentialBytes_roundTrips() {
        val data = ByteArray(32) { it.toByte() }
        val encoded = Bech32.encode("npub", data)
        val (hrp, bytes) = Bech32.decode(encoded)
        assertEquals("npub", hrp)
        assertEquals(data.toHex(), bytes.toHex())
    }

    @Test
    fun encode_customHrp_preservedInDecode() {
        val encoded = Bech32.encode("note", zeros32)
        val (hrp, _) = Bech32.decode(encoded)
        assertEquals("note", hrp)
    }

    // --- hexToNpub / hexToNsec ---

    @Test
    fun hexToNpub_producesNpubPrefix() {
        val npub = hexToNpub(zeros32Hex)
        assertTrue(npub.startsWith("npub1"))
    }

    @Test
    fun hexToNpub_decodesBackToHex() {
        val npub = hexToNpub(zeros32Hex)
        val (hrp, bytes) = Bech32.decode(npub)
        assertEquals("npub", hrp)
        assertEquals(zeros32Hex, bytes.toHex())
    }

    @Test
    fun hexToNsec_producesNsecPrefix() {
        val nsec = hexToNsec(zeros32Hex)
        assertTrue(nsec.startsWith("nsec1"))
    }

    @Test
    fun hexToNsec_decodesBackToHex() {
        val nsec = hexToNsec(zeros32Hex)
        val (hrp, bytes) = Bech32.decode(nsec)
        assertEquals("nsec", hrp)
        assertEquals(zeros32Hex, bytes.toHex())
    }

    // --- normalizePrivateKey ---

    @Test
    fun normalizePrivateKey_fromNsec_returnsHex() {
        val nsec = hexToNsec(zeros32Hex)
        assertEquals(zeros32Hex, normalizePrivateKey(nsec))
    }

    @Test
    fun normalizePrivateKey_fromHex_returnsHex() {
        assertEquals(zeros32Hex, normalizePrivateKey(zeros32Hex))
    }

    @Test
    fun normalizePrivateKey_padsShortHex() {
        val result = normalizePrivateKey("ff")
        assertEquals(64, result.length)
        assertTrue(result.endsWith("ff"))
    }

    @Test
    fun normalizePrivateKey_stripsWhitespace() {
        val padded = "  $zeros32Hex  "
        assertEquals(zeros32Hex, normalizePrivateKey(padded))
    }
}
