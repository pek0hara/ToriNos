package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JpegMetadataTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return b(0xFF, marker, len shr 8, len and 0xFF) + payload
    }

    private val soi = b(0xFF, 0xD8)
    private val eoi = b(0xFF, 0xD9)
    // 標準の JFIF(サムネイル無し、14バイト)。Buzz が許す唯一の APP0。
    private val jfif = segment(0xE0, "JFIF".encodeToByteArray() + b(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
    private val exif = segment(0xE1, "Exif".encodeToByteArray() + b(0, 0, 1, 2, 3))
    private val icc = segment(0xE2, "ICC_PROFILE".encodeToByteArray() + b(0, 1, 1, 9, 9))
    private val comment = segment(0xFE, "hello".encodeToByteArray())
    private val dqt = segment(0xDB, b(0, 1, 2, 3))
    private val sof = segment(0xC0, b(8, 0, 1, 0, 1, 1, 1, 0x11, 0))
    // 本体には、詰め物の FF 00 とリスタートマーカー FF D0 が入る。
    private val sos = segment(0xDA, b(1, 1, 0, 0, 0x3F, 0)) + b(0x12, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56)

    @Test
    fun removesExifIccCommentsAndTrailingData() {
        val input = soi + jfif + exif + icc + comment + dqt + sof + sos + eoi + b(1, 2, 3)
        assertContentEquals(soi + jfif + dqt + sof + sos + eoi, stripJpegMetadata(input))
    }

    @Test
    fun dropsNonCanonicalJfif() {
        // サムネイル付きなどの JFIF も Buzz は断るので、APP0 ごと外す(無くても表示できる)。
        val oddJfif = segment(0xE0, "JFXX".encodeToByteArray() + b(0, 0x10, 1, 2))
        val input = soi + oddJfif + dqt + sof + sos + eoi
        assertContentEquals(soi + dqt + sof + sos + eoi, stripJpegMetadata(input))
    }

    @Test
    fun cleanJpegIsUnchanged() {
        val input = soi + jfif + dqt + sof + sos + eoi
        assertContentEquals(input, stripJpegMetadata(input))
    }

    @Test
    fun notAJpegReturnsNull() {
        assertNull(stripJpegMetadata(b(0x89, 0x50, 0x4E, 0x47)))
        // 途中で切れているものも扱わない(そのまま送り、リレーの判断に任せる)。
        assertNull(stripJpegMetadata(soi + b(0xFF, 0xDB, 0x00)))
    }

    /**
     * macOS の ImageIO(iOS と同じ書き出し)で作った、EXIF・GPS(APP1)・Photoshop(APP13)・P3 の ICC(APP2)入りの JPEG。
     * 並び: SOI, APP0(JFIF 16), APP1(198), APP13(56), APP2(552), SOF0, DHT×4, DQT×2, DRI, SOS, 本体, EOI。
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private val realJpeg = kotlin.io.encoding.Base64.decode(
        "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",
    )

    @Test
    fun realImageIoJpegPassesBuzzCheckAfterStripping() {
        assertEquals(BuzzJpegVerdict.MetadataForbidden, buzzJpegVerdict(realJpeg))
        val stripped = stripJpegMetadata(realJpeg)!!
        assertEquals(BuzzJpegVerdict.Ok, buzzJpegVerdict(stripped))
        // JFIF の後ろの APP1・APP13・APP2 だけを抜き、画像に要る部分は1バイトも変えない。
        val sofOffset = 2 + 18 + 200 + 58 + 554
        assertEquals(0xC0, realJpeg[sofOffset + 1].toInt() and 0xFF)
        assertContentEquals(realJpeg.copyOfRange(0, 20) + realJpeg.copyOfRange(sofOffset, realJpeg.size), stripped)
    }

    private enum class BuzzJpegVerdict { Ok, MetadataForbidden, InvalidImage }

    /** Buzz の `validate_jpeg_metadata_free`(buzz-media/src/validation.rs)をそのまま移したもの。 */
    private fun buzzJpegVerdict(bytes: ByteArray): BuzzJpegVerdict {
        fun u(i: Int) = bytes[i].toInt() and 0xFF
        if (bytes.size < 2 || u(0) != 0xFF || u(1) != 0xD8) return BuzzJpegVerdict.InvalidImage
        var i = 2
        var inScan = false
        while (i < bytes.size) {
            if (u(i) != 0xFF) {
                if (inScan) { i++; continue }
                return BuzzJpegVerdict.InvalidImage
            }
            while (i < bytes.size && u(i) == 0xFF) i++
            if (i >= bytes.size) return BuzzJpegVerdict.InvalidImage
            val marker = u(i)
            i++
            if (inScan && marker == 0x00) continue
            if (marker in 0xD0..0xD7 || marker == 0x01) continue
            if (marker == 0xD9) return if (i == bytes.size) BuzzJpegVerdict.Ok else BuzzJpegVerdict.MetadataForbidden
            if (marker == 0xD8) return BuzzJpegVerdict.InvalidImage
            if (i + 2 > bytes.size) return BuzzJpegVerdict.InvalidImage
            val len = (u(i) shl 8) or u(i + 1)
            if (len < 2) return BuzzJpegVerdict.InvalidImage
            val end = i + len
            if (end > bytes.size) return BuzzJpegVerdict.InvalidImage
            val payload = bytes.copyOfRange(i + 2, end)
            if (marker == 0xE0) {
                val ok = payload.size >= 14 && payload.copyOfRange(0, 5).decodeToString() == "JFIF\u0000" &&
                    payload.size == 14 + 3 * (payload[12].toInt() and 0xFF) * (payload[13].toInt() and 0xFF)
                if (!ok) return BuzzJpegVerdict.MetadataForbidden
            } else if (marker == 0xEE) {
                if (payload.size != 12 || payload.copyOfRange(0, 5).decodeToString() != "Adobe") return BuzzJpegVerdict.MetadataForbidden
            } else if (marker in 0xE1..0xED || marker == 0xEF || marker == 0xFE) {
                return BuzzJpegVerdict.MetadataForbidden
            }
            i = end
            inScan = marker == 0xDA
        }
        return BuzzJpegVerdict.InvalidImage
    }
}
