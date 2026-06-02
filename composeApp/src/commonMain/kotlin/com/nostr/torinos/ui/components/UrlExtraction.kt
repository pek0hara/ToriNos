package com.nostr.torinos.ui.components

private val webUrlRegex = Regex("""https?://\S+""")

data class ExtractedWebUrl(
    val url: String,
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
