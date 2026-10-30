package com.nostr.torinos.model

/** Gyouza encodes each UTF-8 byte as U+E00F0 + byte. No platform regex is needed. */
internal fun decodeHiddenTexts(text: String): List<String> {
    val results = mutableListOf<String>()
    var index = 0
    while (index + 1 < text.length) {
        if (!text.isHiddenByteAt(index)) {
            index++
            continue
        }
        val start = index
        while (index + 1 < text.length && text.isHiddenByteAt(index)) index += 2
        val bytes = ByteArray((index - start) / 2) { offset ->
            (text[start + offset * 2 + 1].code - 0xDCF0).toByte()
        }
        // Ignore malformed sequences rather than presenting replacement characters as a message.
        val decoded = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
        if (!decoded.isNullOrBlank() && decoded.any { !it.isISOControl() && !it.isWhitespace() }) {
            results += decoded
        }
    }
    return results
}

private fun String.isHiddenByteAt(index: Int): Boolean =
    this[index] == '\uDB40' && this[index + 1] in '\uDCF0'..'\uDDEF'
