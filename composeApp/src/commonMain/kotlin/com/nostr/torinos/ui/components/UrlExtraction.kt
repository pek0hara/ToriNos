package com.nostr.torinos.ui.components

private val webUrlRegex = Regex("""https?://\S+""")
private val spotifySearchUriRegex = Regex("""spotify:search:[^\r\n]+""")
private val customEmojiCodeRegex = Regex(""":([a-zA-Z0-9_-]+):""")

data class ExtractedWebUrl(
    val url: String,
    val start: Int,
    val endExclusive: Int,
)

data class ExtractedClickableUri(
    val uri: String,
    val text: String,
    val start: Int,
    val endExclusive: Int,
)

fun extractWebUrls(content: String): List<String> =
    extractWebUrlMatches(content)
        .map { it.url }
        .distinct()
        .toList()

fun extractWebUrlMatches(content: String): List<ExtractedWebUrl> =
    webUrlRegex.findAll(content)
        .mapNotNull { match ->
            val url = match.value.trimUrlBoundary()
            if (url.isBlank()) {
                null
            } else {
                ExtractedWebUrl(
                    url = url,
                    start = match.range.first,
                    endExclusive = match.range.first + url.length,
                )
            }
        }
        .toList()

fun extractClickableUriMatches(content: String): List<ExtractedClickableUri> =
    (extractWebUrlMatches(content).map { match ->
        ExtractedClickableUri(
            uri = match.url,
            text = match.url,
            start = match.start,
            endExclusive = match.endExclusive,
        )
    } + extractSpotifySearchUriMatches(content))
        .sortedBy { it.start }

private fun extractSpotifySearchUriMatches(content: String): List<ExtractedClickableUri> =
    spotifySearchUriRegex.findAll(content)
        .mapNotNull { match ->
            val text = match.value.trimSpotifySearchBoundary()
            val query = text.removePrefix("spotify:search:").trim()
            if (query.isBlank()) {
                null
            } else {
                ExtractedClickableUri(
                    uri = "spotify:search:${query.encodeSpotifySearchQuery()}",
                    text = text,
                    start = match.range.first,
                    endExclusive = match.range.first + text.length,
                )
            }
        }
        .toList()

fun truncateTextPreservingWebUrls(
    text: String,
    maxLength: Int,
    ellipsis: String = "…",
): String {
    if (text.length <= maxLength) return text

    val end = extractWebUrlMatches(text)
        .firstOrNull { match -> match.start < maxLength && maxLength < match.endExclusive }
        ?.endExclusive
        ?: maxLength

    return text.take(end).trimEnd() + ellipsis
}

fun countTextWithCustomEmojis(
    text: String,
    customEmojiShortcodes: Set<String>,
): Int {
    if (customEmojiShortcodes.isEmpty()) return text.length

    var count = 0
    var cursor = 0
    customEmojiCodeRegex.findAll(text).forEach { match ->
        val shortcode = match.groupValues[1]
        if (shortcode !in customEmojiShortcodes) return@forEach

        count += match.range.first - cursor
        count += 1
        cursor = match.range.last + 1
    }
    return count + (text.length - cursor)
}

fun truncateTextPreservingWebUrlsAndCustomEmojis(
    text: String,
    maxLength: Int,
    customEmojiShortcodes: Set<String>,
    ellipsis: String = "…",
): String {
    if (countTextWithCustomEmojis(text, customEmojiShortcodes) <= maxLength) return text

    val rawLimit = findRawEndForCustomEmojiLength(text, maxLength, customEmojiShortcodes)
    val end = extractWebUrlMatches(text)
        .firstOrNull { match -> match.start < rawLimit && rawLimit < match.endExclusive }
        ?.endExclusive
        ?: rawLimit

    return text.take(end).trimEnd() + ellipsis
}

private fun findRawEndForCustomEmojiLength(
    text: String,
    maxLength: Int,
    customEmojiShortcodes: Set<String>,
): Int {
    if (maxLength <= 0) return 0
    if (customEmojiShortcodes.isEmpty()) return maxLength.coerceAtMost(text.length)

    var count = 0
    var cursor = 0
    customEmojiCodeRegex.findAll(text).forEach { match ->
        val shortcode = match.groupValues[1]
        if (shortcode !in customEmojiShortcodes) return@forEach

        val plainLength = match.range.first - cursor
        if (count + plainLength >= maxLength) {
            return cursor + (maxLength - count)
        }
        count += plainLength

        if (count + 1 > maxLength) {
            return match.range.first
        }
        count += 1
        cursor = match.range.last + 1
    }

    return (cursor + (maxLength - count)).coerceAtMost(text.length)
}

private fun String.trimUrlBoundary(): String =
    trimEnd('.', ',', ';', ':', ')', ']', '}', '>', '"', '\'')

private fun String.trimSpotifySearchBoundary(): String =
    trimEnd('.', ',', ';', ')', ']', '}', '>', '"', '\'')

private fun String.encodeSpotifySearchQuery(): String {
    val bytes = encodeToByteArray()
    return buildString {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            if (value.isUriUnreserved()) {
                append(value.toChar())
            } else {
                append('%')
                append(value.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

private fun Int.isUriUnreserved(): Boolean =
    this in 'A'.code..'Z'.code ||
        this in 'a'.code..'z'.code ||
        this in '0'.code..'9'.code ||
        this == '-'.code ||
        this == '.'.code ||
        this == '_'.code ||
        this == '~'.code
