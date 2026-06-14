package com.nostr.torinos.ui.components

private val webUrlRegex = Regex("""https?://\S+""")
private val spotifySearchUriRegex = Regex("""spotify:search:[^\r\n]+""")

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
