package com.nostr.torinos.article

import com.nostr.torinos.model.decodeNostrEventReference
import com.nostr.torinos.model.extractNostrEventReferences

internal sealed interface StructuredConversion {
    data class Ok(val draft: ArticleDraft) : StructuredConversion
    data class Blocked(val reason: String) : StructuredConversion
}

private val standaloneReference = Regex("""^(?:nostr:)?([a-z0-9]+1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)$""", RegexOption.IGNORE_CASE)

/**
 * Turns a free Markdown matometr back into cards. Only a reference alone on its line becomes a card;
 * the text before the first one is the introduction and the text after each one is its commentary.
 * Anything a card cannot hold (a reference inside a sentence, list or quote, or the same post twice) blocks the
 * conversion rather than moving it, and the result must render exactly like the original.
 */
internal fun toStructuredMatometr(draft: ArticleDraft): StructuredConversion {
    if (draft.mode != ArticleDraftMode.MatometrMarkdown) return StructuredConversion.Blocked("本文を直接書く形式ではありません")
    if (!ArticleMarkdownBoundaryValidator.isClosed(draft.bodyMarkdown)) return StructuredConversion.Blocked("コードブロックを閉じてください")
    val lines = draft.bodyMarkdown.lines().toMutableList()
    while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)

    var fence: ArticleMarkdownBoundaryValidator.Fence? = null
    val inCode = BooleanArray(lines.size)
    lines.forEachIndexed { index, line ->
        val active = fence
        // Indented lines render as code too, so a reference there is text, not a quote.
        if (active == null) { fence = ArticleMarkdownBoundaryValidator.opening(line)
            inCode[index] = fence != null || line.trimEnd().let { it.startsWith("    ") || it.startsWith("\t") } }
        else { inCode[index] = true; if (ArticleMarkdownBoundaryValidator.closes(line, active)) fence = null }
    }
    val referenceAt = lines.indices.associateWith { index ->
        if (inCode[index]) null
        else standaloneReference.matchEntire(lines[index].trim())?.let { decodeNostrEventReference(it.groupValues[1].lowercase()) }
    }
    for (index in lines.indices) {
        if (inCode[index] || referenceAt[index] != null) continue
        if (extractNostrEventReferences(articleReferenceText(lines[index])).isNotEmpty()) {
            return StructuredConversion.Blocked("${index + 1}行目の文中に投稿の参照があります。参照だけの行にするとカードに戻せます")
        }
    }

    val parts = mutableListOf(mutableListOf<String>())
    val references = mutableListOf<MatometrReference>()
    lines.forEachIndexed { index, line ->
        val reference = referenceAt[index]
        if (reference == null) parts.last() += line
        else {
            if (references.any { it.eventId == reference.eventId }) {
                return StructuredConversion.Blocked("同じ投稿が2回以上参照されています（${index + 1}行目）")
            }
            references += MatometrReference(reference.eventId.lowercase(), reference.authorPubkey?.lowercase(),
                reference.relayUrls)
            parts += mutableListOf<String>()
        }
    }
    if (references.isEmpty()) return StructuredConversion.Blocked("参照だけの行に置かれた投稿がありません")
    fun List<String>.text() = dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
    val converted = draft.copy(mode = ArticleDraftMode.MatometrStructured, bodyMarkdown = parts.first().text(),
        references = references.mapIndexed { index, reference -> reference.copy(commentaryMarkdown = parts[index + 1].text()) })

    if (converted.content.length > ARTICLE_CONTENT_MAX_LENGTH) return StructuredConversion.Blocked("変換後の本文が10,000文字を超えます")
    validateMatometr(converted)?.let { return StructuredConversion.Blocked(it) }
    if (renderedShape(converted.content) != renderedShape(draft.content)) {
        return StructuredConversion.Blocked("カードに戻すと表示が変わるため変換できません")
    }
    return StructuredConversion.Ok(converted)
}

/** What a reader sees, in order; reference lines without a blank line between them render the same as separate ones. */
private fun renderedShape(content: String): List<Any> =
    parseArticleMarkdown(content, emptyList()).sections.flatMap { section ->
        listOfNotNull(section.block.takeIf { it != MarkdownBlock.Blank }) + section.quoteIds
    }
