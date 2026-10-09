package com.nostr.torinos.article

/** Shared fence rules for rendering, validation and appending references. */
internal object ArticleMarkdownBoundaryValidator {
    data class Fence(val character: Char, val length: Int)
    private val opening = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
    fun opening(line: String): Fence? {
        val match = opening.matchEntire(line) ?: return null
        val marker = match.groupValues[1]
        if (marker.first() == '`' && '`' in match.groupValues[2]) return null
        return Fence(marker.first(), marker.length)
    }
    fun closes(line: String, fence: Fence): Boolean {
        val trimmed = line.dropWhile { it == ' ' }
        if (line.length - trimmed.length > 3) return false
        val count = trimmed.takeWhile { it == fence.character }.length
        return count >= fence.length && trimmed.drop(count).isBlank()
    }
    fun isClosed(content: String): Boolean {
        var fence: Fence? = null
        content.lines().forEach { line ->
            val active = fence
            if (active == null) fence = opening(line)
            else if (closes(line, active)) fence = null
        }
        return fence == null
    }
}

/** Mask inline code and images while preserving offsets for reference removal. */
internal fun articleReferenceText(text: String): String {
    val chars = text.toCharArray()
    fun mask(start: Int, end: Int) { for (i in start until end) chars[i] = ' ' }
    var i = 0
    while (i < text.length) {
        if (text[i] == '`') {
            val count = text.substring(i).takeWhile { it == '`' }.length
            val marker = "`".repeat(count)
            var end = text.indexOf(marker, i + count)
            while (end >= 0 && ((end > 0 && text[end - 1] == '`') || text.getOrNull(end + count) == '`')) {
                end = text.indexOf(marker, end + count)
            }
            if (end >= 0) { mask(i, end + count); i = end + count; continue }
            i += count; continue
        }
        if (text.startsWith("![", i)) {
            val labelEnd = text.indexOf("](", i + 2)
            val end = if (labelEnd >= 0) text.indexOf(')', labelEnd + 2) else -1
            if (end >= 0) { mask(i, end + 1); i = end + 1; continue }
        }
        i++
    }
    return chars.concatToString()
}
internal fun stripArticleEventUris(text: String): String {
    val eligible = articleReferenceText(text)
    val uri = Regex("""\b(?:nostr:)?([a-z0-9]+1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)""", RegexOption.IGNORE_CASE)
    return uri.replace(text) { match ->
        if (eligible.substring(match.range).isNotBlank() &&
            com.nostr.torinos.model.decodeNostrEventReference(match.groupValues[1]) != null) "" else match.value
    }.trim()
}
