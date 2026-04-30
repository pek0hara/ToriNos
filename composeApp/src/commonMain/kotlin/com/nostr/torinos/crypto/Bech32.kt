package com.nostr.torinos.crypto

/**
 * NIP-19 用の最小 bech32 デコーダ。
 * nsec1... / npub1... を hex bytes に変換する。
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_MAP = IntArray(128) { -1 }.also { map ->
        CHARSET.forEachIndexed { i, c -> map[c.code] = i }
    }

    /** bech32 文字列を (hrp, data bytes) に変換。検証失敗時は例外。 */
    fun decode(bech32: String): Pair<String, ByteArray> {
        val lower = bech32.lowercase()
        val sepIdx = lower.lastIndexOf('1')
        require(sepIdx > 0) { "セパレータ '1' が見つかりません" }

        val hrp = lower.substring(0, sepIdx)
        val dataPart = lower.substring(sepIdx + 1)
        require(dataPart.length >= 6) { "データ部が短すぎます" }

        val values = IntArray(dataPart.length) { i ->
            val v = CHARSET_MAP.getOrElse(dataPart[i].code) { -1 }
            require(v != -1) { "無効な文字: ${dataPart[i]}" }
            v
        }

        require(verifyChecksum(hrp, values)) { "チェックサムが一致しません" }

        // チェックサム 6 文字を除いたデータを 5bit → 8bit 変換
        val bytes = convertBits(values.copyOf(values.size - 6), 5, 8, pad = false)
        return Pair(hrp, bytes)
    }

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add(((acc shr bits) and maxv).toByte())
            }
        }
        if (pad && bits > 0) {
            result.add(((acc shl (toBits - bits)) and maxv).toByte())
        }
        return result.toByteArray()
    }

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1FFFFFF) shl 5) xor v
            if (top and 1 != 0) chk = chk xor 0x3B6A57B2
            if (top and 2 != 0) chk = chk xor 0x26508E6D
            if (top and 4 != 0) chk = chk xor 0x1EA119FA
            if (top and 8 != 0) chk = chk xor 0x3D4233DD
            if (top and 16 != 0) chk = chk xor 0x2A1462B3
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        hrp.forEachIndexed { i, c ->
            result[i] = c.code ushr 5
            result[i + hrp.length + 1] = c.code and 31
        }
        result[hrp.length] = 0
        return result
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean =
        polymod(hrpExpand(hrp) + data) == 1

    /** ByteArray (8-bit) を 5-bit IntArray に変換（エンコード用） */
    private fun convertBitsFrom8(data: ByteArray): IntArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Int>()
        for (b in data) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                result.add((acc shr bits) and 31)
            }
        }
        if (bits > 0) result.add((acc shl (5 - bits)) and 31)
        return result.toIntArray()
    }

    private fun createChecksum(hrp: String, data: IntArray): IntArray {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val poly = polymod(values) xor 1
        return IntArray(6) { i -> (poly shr (5 * (5 - i))) and 31 }
    }

    /** bytes を bech32 エンコードして hrp1... 形式の文字列を返す */
    fun encode(hrp: String, bytes: ByteArray): String {
        val data = convertBitsFrom8(bytes)
        val checksum = createChecksum(hrp, data)
        return hrp + "1" + (data.toList() + checksum.toList()).joinToString("") { CHARSET[it].toString() }
    }
}

/** hex 秘密鍵を nsec1... 形式に変換する */
fun hexToNsec(hexPrivKey: String): String = Bech32.encode("nsec", hexPrivKey.fromHex())

/** hex 公開鍵を npub1... 形式に変換する */
fun hexToNpub(hexPubKey: String): String = Bech32.encode("npub", hexPubKey.fromHex())

/**
 * nsec1... 形式の文字列を hex 秘密鍵文字列に変換する。
 * すでに hex の場合はそのまま返す。
 */
fun normalizePrivateKey(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.startsWith("nsec1", ignoreCase = true) -> {
            val (hrp, bytes) = Bech32.decode(trimmed)
            require(hrp == "nsec") { "プレフィックスが nsec ではありません: $hrp" }
            require(bytes.size == 32) { "秘密鍵のバイト数が不正: ${bytes.size}" }
            bytes.toHex()
        }
        else -> trimmed.padStart(64, '0') // hex（先頭ゼロ省略対応）
    }
}
