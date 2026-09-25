package com.nostr.torinos.article

import com.nostr.torinos.model.extractNostrEventReferences
import com.nostr.torinos.model.stripNostrEventUris

internal sealed interface MarkdownBlock {
    data object Blank : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class ListItem(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Image(val alt: String, val url: String) : MarkdownBlock
}

internal sealed interface MarkdownInline {
    data class Text(val text: String) : MarkdownInline
    data class Code(val text: String) : MarkdownInline
    data class Bold(val children: List<MarkdownInline>) : MarkdownInline
    data class Italic(val children: List<MarkdownInline>) : MarkdownInline
    data class Link(val url: String, val children: List<MarkdownInline>) : MarkdownInline
}

/**
 * 描画単位の1ブロック。[block]は`nostr:`イベント参照を取り除いた表示用で、取り除いた結果が空なら null。
 * [quoteIds]はブロック直後に表示する引用プレビューのイベントID。
 */
internal data class ArticleMarkdownSection(
    val block: MarkdownBlock?,
    val quoteIds: List<String>,
)

internal data class ParsedArticleMarkdown(
    val sections: List<ArticleMarkdownSection>,
    /** 本文中に参照されず、末尾へまとめて表示する引用ID(`q`タグ由来など)。 */
    val trailingQuoteIds: List<String>,
)

internal fun parseArticleMarkdown(
    content: String,
    articleQuoteIds: List<String>,
): ParsedArticleMarkdown {
    val inlineQuoteIds = mutableSetOf<String>()
    val sections = parseMarkdownBlocks(content).map { block ->
        val quoteIds = when (block) {
            MarkdownBlock.Blank,
            is MarkdownBlock.Code,
            is MarkdownBlock.Image,
            -> emptyList()
            is MarkdownBlock.Heading -> eventReferenceIds(block.text)
            is MarkdownBlock.Quote -> eventReferenceIds(block.text)
            is MarkdownBlock.ListItem -> eventReferenceIds(block.text)
            is MarkdownBlock.Paragraph -> eventReferenceIds(block.text)
        }
        inlineQuoteIds += quoteIds
        val displayBlock = when (block) {
            MarkdownBlock.Blank,
            is MarkdownBlock.Code,
            is MarkdownBlock.Image,
            -> block
            is MarkdownBlock.Heading -> stripNostrEventUris(block.text).takeIf { it.isNotBlank() }?.let { block.copy(text = it) }
            is MarkdownBlock.Quote -> stripNostrEventUris(block.text).takeIf { it.isNotBlank() }?.let { block.copy(text = it) }
            is MarkdownBlock.ListItem -> stripNostrEventUris(block.text).takeIf { it.isNotBlank() }?.let { block.copy(text = it) }
            is MarkdownBlock.Paragraph -> stripNostrEventUris(block.text).takeIf { it.isNotBlank() }?.let { block.copy(text = it) }
        }
        ArticleMarkdownSection(block = displayBlock, quoteIds = quoteIds)
    }
    return ParsedArticleMarkdown(
        sections = sections,
        trailingQuoteIds = articleQuoteIds.filterNot { it in inlineQuoteIds },
    )
}

private fun eventReferenceIds(text: String): List<String> =
    extractNostrEventReferences(text).map { it.eventId }

internal fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val codeLines = mutableListOf<String>()
    var inCodeBlock = false

    content.lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        if (inCodeBlock) {
            if (line.trimStart().startsWith("```")) {
                blocks += MarkdownBlock.Code(codeLines.joinToString("\n"))
                codeLines.clear()
                inCodeBlock = false
            } else {
                codeLines += rawLine
            }
            return@forEach
        }

        val imageMatch = markdownImageRegex.matchEntire(line.trim())
        when {
            line.trimStart().startsWith("```") -> inCodeBlock = true
            line.isBlank() -> blocks += MarkdownBlock.Blank
            imageMatch != null -> blocks += MarkdownBlock.Image(
                alt = imageMatch.groupValues[1],
                url = imageMatch.groupValues[2],
            )
            line.startsWith("### ") -> blocks += MarkdownBlock.Heading(3, line.removePrefix("### "))
            line.startsWith("## ") -> blocks += MarkdownBlock.Heading(2, line.removePrefix("## "))
            line.startsWith("# ") -> blocks += MarkdownBlock.Heading(1, line.removePrefix("# "))
            line.startsWith(">") -> blocks += MarkdownBlock.Quote(line.removePrefix(">").trim())
            line.startsWith("- ") || line.startsWith("* ") -> blocks += MarkdownBlock.ListItem(line.drop(2))
            line.startsWith("    ") -> blocks += MarkdownBlock.Code(line.trimStart())
            else -> blocks += MarkdownBlock.Paragraph(line)
        }
    }

    if (inCodeBlock) {
        blocks += MarkdownBlock.Code(codeLines.joinToString("\n"))
    }
    return blocks
}

private val markdownImageRegex = Regex("""!\[([^]]*)]\((https://[^)\s]+)\)""")

/** インライン記法(コード、太字、斜体、リンク)を解析する。閉じていない記号は文字として残す。 */
internal fun parseMarkdownInline(text: String): List<MarkdownInline> {
    val result = mutableListOf<MarkdownInline>()
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            result += MarkdownInline.Text(plain.toString())
            plain.clear()
        }
    }

    fun emit(node: MarkdownInline) {
        flushPlain()
        result += node
    }

    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("`", index) -> {
                val end = text.indexOf('`', startIndex = index + 1)
                if (end > index) {
                    emit(MarkdownInline.Code(text.substring(index + 1, end)))
                    index = end + 1
                } else {
                    plain.append(text[index])
                    index += 1
                }
            }
            text.startsWith("**", index) || text.startsWith("__", index) -> {
                val marker = text.substring(index, index + 2)
                val end = text.indexOf(marker, startIndex = index + 2)
                if (end > index) {
                    emit(MarkdownInline.Bold(parseMarkdownInline(text.substring(index + 2, end))))
                    index = end + 2
                } else {
                    plain.append(text[index])
                    index += 1
                }
            }
            text[index] == '*' || text[index] == '_' -> {
                val end = text.indexOf(text[index], startIndex = index + 1)
                if (end > index + 1) {
                    emit(MarkdownInline.Italic(parseMarkdownInline(text.substring(index + 1, end))))
                    index = end + 1
                } else {
                    plain.append(text[index])
                    index += 1
                }
            }
            text[index] == '[' -> {
                val labelEnd = text.indexOf("](", startIndex = index + 1)
                val urlEnd = if (labelEnd > index) text.indexOf(')', startIndex = labelEnd + 2) else -1
                if (labelEnd > index && urlEnd > labelEnd + 2) {
                    emit(
                        MarkdownInline.Link(
                            url = text.substring(labelEnd + 2, urlEnd),
                            children = parseMarkdownInline(text.substring(index + 1, labelEnd)),
                        ),
                    )
                    index = urlEnd + 1
                } else {
                    plain.append(text[index])
                    index += 1
                }
            }
            else -> {
                plain.append(text[index])
                index += 1
            }
        }
    }
    flushPlain()
    return result
}
