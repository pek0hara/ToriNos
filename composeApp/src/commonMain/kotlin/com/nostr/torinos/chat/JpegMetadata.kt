package com.nostr.torinos.chat

/**
 * JPEG から、表示に要らない付加情報(EXIF・ICC プロファイル・XMP・コメントなど)と、終わりの後ろのデータを外す。
 *
 * Buzz は位置情報などの漏れを防ぐため、標準の JFIF・Adobe ヘッダー以外の APPn やコメントが入った JPEG を
 * 422 で断る(`buzz-media` の `validate_jpeg_metadata_free`)。iOS の `UIImageJPEGRepresentation` は EXIF を、
 * P3 の画像(スクリーンショットなど)には ICC プロファイルも必ず入れるので、送る前に外す。
 * ICC を外すと P3 の色は sRGB として表示され、わずかに色味が変わることがある。
 *
 * JPEG として読めないとき(途中で切れているなど)は null を返す。
 */
fun stripJpegMetadata(bytes: ByteArray): ByteArray? {
    fun u(i: Int) = bytes[i].toInt() and 0xFF
    if (bytes.size < 4 || u(0) != 0xFF || u(1) != 0xD8) return null
    val out = ByteArray(bytes.size)
    var o = 0
    fun put(b: Int) { out[o++] = b.toByte() }
    put(0xFF)
    put(0xD8)
    var i = 2
    while (i < bytes.size) {
        if (u(i) != 0xFF) return null
        // 区切りの前の詰め物の FF は飛ばす。
        while (i < bytes.size && u(i) == 0xFF) i++
        if (i >= bytes.size) return null
        val marker = u(i)
        i++
        when {
            marker == 0xD9 -> {
                // 本体の後ろに付いたデータ(マルチピクチャの2枚目など)は捨てる。
                put(0xFF)
                put(0xD9)
                return out.copyOf(o)
            }
            marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> return null
        }
        if (i + 2 > bytes.size) return null
        val length = (u(i) shl 8) or u(i + 1)
        if (length < 2) return null
        val end = i + length
        if (end > bytes.size) return null
        val keep = when (marker) {
            0xE0 -> isCanonicalJfif(bytes.copyOfRange(i + 2, end))
            0xEE -> length - 2 == 12 && bytes.copyOfRange(i + 2, i + 7).decodeToString() == "Adobe"
            in 0xE1..0xEF, 0xFE -> false
            else -> true
        }
        if (keep) {
            put(0xFF)
            put(marker)
            bytes.copyInto(out, o, i, end)
            o += end - i
        }
        i = end
        if (marker == 0xDA) {
            // 画像本体。次の区切り(詰め物の FF 00 とリスタート FF D0〜D7 以外)までそのまま写す。
            val start = i
            while (i < bytes.size) {
                if (u(i) == 0xFF && i + 1 < bytes.size) {
                    val next = u(i + 1)
                    if (next != 0x00 && next !in 0xD0..0xD7 && next != 0xFF) break
                }
                i++
            }
            bytes.copyInto(out, o, start, i)
            o += i - start
        }
    }
    return null
}

/** Buzz が許す JFIF: 識別子 `JFIF\0` で、長さがサムネイルの寸法とちょうど合う。 */
private fun isCanonicalJfif(payload: ByteArray): Boolean {
    if (payload.size < 14) return false
    if (payload.copyOfRange(0, 5).decodeToString() != "JFIF\u0000") return false
    val w = payload[12].toInt() and 0xFF
    val h = payload[13].toInt() and 0xFF
    return payload.size == 14 + 3 * w * h
}
