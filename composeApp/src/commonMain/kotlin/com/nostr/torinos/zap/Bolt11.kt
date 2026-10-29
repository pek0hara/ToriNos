package com.nostr.torinos.zap

import com.nostr.torinos.crypto.Bech32
import com.nostr.torinos.crypto.toHex

/** BOLT11 請求書のうち、Zap の検査と集計に使う部分。署名は検証しない（ウォレットが行う）。 */
data class Bolt11Invoice(
    /** `bc`（メインネット）、`tb`（テストネット）、`bcrt`、`tbs` など。 */
    val network: String,
    /** 請求額（msats）。額の指定がない請求書は null。 */
    val amountMsats: Long?,
    /** 発行時刻（UNIX 秒）。 */
    val timestamp: Long,
    /** 有効期間（秒）。指定がなければ 3600。 */
    val expirySeconds: Long,
    val paymentHash: String?,
    /** `h` タグ（description hash、16進）。 */
    val descriptionHash: String?,
) {
    val isMainnet: Boolean get() = network == "bc"

    fun isExpired(nowSeconds: Long): Boolean = nowSeconds >= timestamp + expirySeconds
}

private const val TIMESTAMP_WORDS = 7
private const val SIGNATURE_WORDS = 104
private const val DEFAULT_EXPIRY_SECONDS = 3_600L
private const val TAG_PAYMENT_HASH = 1
private const val TAG_DESCRIPTION_HASH = 23
private const val TAG_EXPIRY = 6
private val NETWORKS = listOf("bcrt", "bc", "tbs", "tb", "sb")
private val AMOUNT = Regex("([0-9]+)([munp]?)")

/** 請求書を読む。`lightning:` の接頭辞は外す。読めなければ null。 */
fun parseBolt11(invoice: String): Bolt11Invoice? = runCatching {
    val text = invoice.trim().let { if (it.startsWith("lightning:", ignoreCase = true)) it.substring(10) else it }
    val (hrp, words) = Bech32.decodeWords(text)
    if (!hrp.startsWith("ln") || words.size < TIMESTAMP_WORDS + SIGNATURE_WORDS) return null
    val rest = hrp.substring(2)
    val network = NETWORKS.firstOrNull { rest.startsWith(it) } ?: return null
    val amountMsats = parseAmount(rest.substring(network.length)) ?: return null
    val timestamp = words.toLong(0, TIMESTAMP_WORDS)
    var paymentHash: String? = null
    var descriptionHash: String? = null
    var expiry = DEFAULT_EXPIRY_SECONDS
    var index = TIMESTAMP_WORDS
    val end = words.size - SIGNATURE_WORDS
    while (index + 3 <= end) {
        val type = words[index]
        val length = words[index + 1] * 32 + words[index + 2]
        val start = index + 3
        if (start + length > end) return null
        val field = words.copyOfRange(start, start + length)
        // 長さの違うフィールドは読み飛ばす（BOLT11 の規定）。
        when (type) {
            TAG_PAYMENT_HASH -> if (length == 52 && paymentHash == null) paymentHash = Bech32.wordsToBytes(field).toHex()
            TAG_DESCRIPTION_HASH -> if (length == 52 && descriptionHash == null) descriptionHash = Bech32.wordsToBytes(field).toHex()
            TAG_EXPIRY -> if (length in 1..12) expiry = field.toLong(0, length)
        }
        index = start + length
    }
    Bolt11Invoice(
        network = network,
        amountMsats = amountMsats.takeIf { it >= 0 },
        timestamp = timestamp,
        expirySeconds = expiry,
        paymentHash = paymentHash,
        descriptionHash = descriptionHash,
    )
}.getOrNull()

/** 額の部分を msats にする。額なしは -1、形式が正しくなければ null。 */
private fun parseAmount(text: String): Long? {
    if (text.isEmpty()) return -1
    val match = AMOUNT.matchEntire(text) ?: return null
    val digits = match.groupValues[1]
    if (digits.length > 15 || (digits.length > 1 && digits.startsWith("0"))) return null
    val value = digits.toLong()
    // 1 BTC = 10^11 msats。
    val factor = when (match.groupValues[2]) {
        "" -> 100_000_000_000L
        "m" -> 100_000_000L
        "u" -> 100_000L
        "n" -> 100L
        "p" -> return if (value % 10 == 0L) value / 10 else null
        else -> return null
    }
    return if (value > Long.MAX_VALUE / factor) null else value * factor
}

private fun IntArray.toLong(from: Int, count: Int): Long {
    var result = 0L
    for (i in from until from + count) result = (result shl 5) or this[i].toLong()
    return result
}
