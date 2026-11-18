package com.nostr.torinos.model

private val httpSchemeRegex = Regex("""^https?://""", RegexOption.IGNORE_CASE)

/**
 * 本文中の URL を表示用に短くする。リンク先は元の URL のまま使う。
 * スキームと先頭の `www.`、末尾の `/` を外し、パーセントエンコードされた日本語などは読める文字に戻す。
 * `%2F` や `%20` のような ASCII の符号は、戻すと別の URL に見えるので残す。
 */
fun shortenUrlForDisplay(url: String, maxLength: Int = 40): String {
    val scheme = httpSchemeRegex.find(url) ?: return url
    var rest = url.substring(scheme.range.last + 1)
    if (rest.startsWith("www.", ignoreCase = true)) rest = rest.substring(4)
    // ホスト名は手を加えずに必ず全部出す。文字を戻したり途中で切ったりすると、別のサイトに見せかけられる。
    val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val host = rest.substring(0, hostEnd)
    val display = host + decodeNonAsciiPercentEncoding(rest.substring(hostEnd)).removeSuffix("/")
    if (display.length <= maxLength) return display

    var end = maxOf(maxLength - 1, minOf(host.length + 1, display.length))
    if (end >= display.length) return display
    if (display[end - 1].isHighSurrogate()) end--
    return display.substring(0, end) + "…"
}

/** 連続した `%XX` のうち、UTF-8 として正しい非 ASCII 文字の並びだけを文字に戻す。 */
private fun decodeNonAsciiPercentEncoding(text: String): String {
    if ('%' !in text) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (text[i] != '%') {
            out.append(text[i])
            i++
            continue
        }
        // 連続した %XX をまとめて読む。
        val bytes = ArrayList<Byte>()
        var j = i
        while (j + 2 < text.length && text[j] == '%') {
            val value = text.substring(j + 1, j + 3).toIntOrNull(16) ?: break
            bytes.add(value.toByte())
            j += 3
        }
        if (bytes.isEmpty()) {
            out.append('%')
            i++
            continue
        }
        appendDecodedRun(out, text.substring(i, j), bytes.toByteArray())
        i = j
    }
    return out.toString()
}

/** バイト列を UTF-8 の1文字ずつに分け、非 ASCII の正しい文字だけ戻す。それ以外は元の `%XX` を残す。 */
private fun appendDecodedRun(out: StringBuilder, encoded: String, bytes: ByteArray) {
    var k = 0
    while (k < bytes.size) {
        val lead = bytes[k].toInt() and 0xFF
        val length = when {
            lead and 0xE0 == 0xC0 && lead >= 0xC2 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 && lead <= 0xF4 -> 4
            else -> 0
        }
        val valid = length > 0 &&
            k + length <= bytes.size &&
            (1 until length).all { (bytes[k + it].toInt() and 0xC0) == 0x80 }
        if (valid) {
            val decoded = bytes.decodeToString(k, k + length)
            // 不正な並び（過長表現やサロゲート）は置換文字になるので戻さない。
            if ('\uFFFD' !in decoded && decoded.isSafeToShowDecoded()) {
                out.append(decoded)
                k += length
                continue
            }
        }
        out.append(encoded, k * 3, k * 3 + 3)
        k++
    }
}

/**
 * 表示に戻しても安全な文字か。見えない文字や文字の並びを変える制御文字（U+202E など）、空白類は、
 * 戻すとリンク先を偽装できるので `%XX` のまま残す。
 */
private fun String.isSafeToShowDecoded(): Boolean {
    if (length == 1) {
        return when (this[0].category) {
            CharCategory.FORMAT,
            CharCategory.CONTROL,
            CharCategory.SPACE_SEPARATOR,
            CharCategory.LINE_SEPARATOR,
            CharCategory.PARAGRAPH_SEPARATOR,
            CharCategory.PRIVATE_USE,
            CharCategory.UNASSIGNED,
            CharCategory.SURROGATE,
            -> false
            else -> true
        }
    }
    // 4バイトの文字（絵文字など）。見えないタグ文字のある U+E0000 以降と私用領域は戻さない。
    if (length != 2 || !this[0].isHighSurrogate() || !this[1].isLowSurrogate()) return false
    val codePoint = 0x10000 + ((this[0].code - 0xD800) shl 10) + (this[1].code - 0xDC00)
    return codePoint < 0xE0000
}
