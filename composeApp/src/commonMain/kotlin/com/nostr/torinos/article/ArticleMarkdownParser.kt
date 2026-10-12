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

    /** GFMの表。[rows]の各行は見出しと同じ列数にそろえてある。セルはインライン記法を含む生の文字列。 */
    data class Table(
        val alignments: List<MarkdownTableAlignment>,
        val header: List<String>,
        val rows: List<List<String>>,
    ) : MarkdownBlock
}

internal enum class MarkdownTableAlignment { None, Left, Center, Right }

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
            is MarkdownBlock.Table -> block.cells().flatMap(::eventReferenceIds)
        }
        inlineQuoteIds += quoteIds
        val displayBlock = when (block) {
            MarkdownBlock.Blank,
            is MarkdownBlock.Code,
            is MarkdownBlock.Image,
            -> block
            is MarkdownBlock.Heading -> stripArticleEventUris(block.text).takeIf { it.isNotBlank() }?.let { block.copy(text = it) }
            is MarkdownBlock.Quote -> stripArticleEventUris(block.text).takeIf { it.isNotBlank() }?.let { block.copy(text = it) }
            is MarkdownBlock.ListItem -> stripArticleEventUris(block.text).takeIf { it.isNotBlank() }?.let { block.copy(text = it) }
            is MarkdownBlock.Paragraph -> stripArticleEventUris(block.text).takeIf { it.isNotBlank() }?.let { block.copy(text = it) }
            // 表は参照を除いて空のセルが出ても形を保つ。
            is MarkdownBlock.Table -> block.copy(
                header = block.header.map(::stripArticleEventUris),
                rows = block.rows.map { row -> row.map(::stripArticleEventUris) },
            )
        }
        ArticleMarkdownSection(block = displayBlock, quoteIds = quoteIds)
    }
    return ParsedArticleMarkdown(
        sections = sections,
        trailingQuoteIds = articleQuoteIds.filterNot { it in inlineQuoteIds },
    )
}

private fun MarkdownBlock.Table.cells(): List<String> = header + rows.flatten()

private fun eventReferenceIds(text: String): List<String> =
    extractNostrEventReferences(articleReferenceText(text)).map { it.eventId }

internal fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val codeLines = mutableListOf<String>()
    var fence: ArticleMarkdownBoundaryValidator.Fence? = null

    val lines = content.lines()
    var index = 0
    while (index < lines.size) {
        val rawLine = lines[index]
        val line = rawLine.trimEnd()
        index += 1
        val activeFence = fence
        if (activeFence != null) {
            if (ArticleMarkdownBoundaryValidator.closes(line, activeFence)) {
                blocks += MarkdownBlock.Code(codeLines.joinToString("\n"))
                codeLines.clear()
                fence = null
            } else {
                codeLines += rawLine
            }
            continue
        }

        val opening = ArticleMarkdownBoundaryValidator.opening(line)
        if (opening != null) {
            fence = opening
            continue
        }
        val block = lineBlock(line)
        // 段落になる行の次が区切り行なら、表の見出し行として扱う。
        val table = if (block is MarkdownBlock.Paragraph) {
            lines.getOrNull(index)?.let { tableStart(line, it.trimEnd()) }
        } else {
            null
        }
        if (table == null) {
            blocks += block
            continue
        }
        index += 1
        val rows = mutableListOf<List<String>>()
        // 本文行は、空行かほかのブロックが始まるまで続く。縦棒のない行も1列目だけの行になる。
        while (index < lines.size) {
            val rowLine = lines[index].trimEnd()
            if (ArticleMarkdownBoundaryValidator.opening(rowLine) != null || lineBlock(rowLine) !is MarkdownBlock.Paragraph) break
            rows += splitTableRow(rowLine).fitTo(table.header.size)
            index += 1
        }
        blocks += table.copy(rows = rows)
    }

    if (fence != null) {
        blocks += MarkdownBlock.Code(codeLines.joinToString("\n"))
    }
    return blocks
}

/** フェンス以外の1行を分類する。 */
private fun lineBlock(line: String): MarkdownBlock {
    val imageMatch = markdownImageRegex.matchEntire(line.trim())
    return when {
        line.isBlank() -> MarkdownBlock.Blank
        imageMatch != null -> MarkdownBlock.Image(
            alt = imageMatch.groupValues[1],
            url = imageMatch.groupValues[2],
        )
        line.startsWith("### ") -> MarkdownBlock.Heading(3, line.removePrefix("### "))
        line.startsWith("## ") -> MarkdownBlock.Heading(2, line.removePrefix("## "))
        line.startsWith("# ") -> MarkdownBlock.Heading(1, line.removePrefix("# "))
        line.startsWith(">") -> MarkdownBlock.Quote(line.removePrefix(">").trim())
        line.startsWith("- ") || line.startsWith("* ") -> MarkdownBlock.ListItem(line.drop(2))
        (line.startsWith("    ") || line.startsWith("\t")) -> MarkdownBlock.Code(line.trimStart())
        else -> MarkdownBlock.Paragraph(line)
    }
}

/**
 * GFMの表の開始を判定し、本文行が空の表を返す。見出し行と区切り行の両方に縦棒があり、
 * 区切り行のセルがすべて`:?-+:?`で、列数が見出し行と同じときだけ表とする。
 * 縦棒を必須にするのは、見出しの下の`---`（水平線）を1列の表と誤認しないため。
 */
private fun tableStart(headerLine: String, delimiterLine: String): MarkdownBlock.Table? {
    if (!headerLine.hasUnescapedPipe() || !delimiterLine.hasUnescapedPipe()) return null
    val header = splitTableRow(headerLine)
    val delimiters = splitTableRow(delimiterLine)
    if (header.size != delimiters.size || delimiters.any { !tableDelimiterRegex.matches(it) }) return null
    return MarkdownBlock.Table(
        alignments = delimiters.map { cell ->
            val left = cell.startsWith(':')
            val right = cell.endsWith(':')
            when {
                left && right -> MarkdownTableAlignment.Center
                left -> MarkdownTableAlignment.Left
                right -> MarkdownTableAlignment.Right
                else -> MarkdownTableAlignment.None
            }
        },
        header = header,
        rows = emptyList(),
    )
}

private val tableDelimiterRegex = Regex(":?-+:?")

private fun String.hasUnescapedPipe(): Boolean =
    indices.any { this[it] == '|' && (it == 0 || this[it - 1] != '\\') }

/** 表の1行をセルに分ける。先頭と末尾の縦棒は省略でき、`\|`はセル内の縦棒になる。 */
private fun splitTableRow(line: String): List<String> {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    val trimmed = line.trim()
    var index = if (trimmed.startsWith('|')) 1 else 0
    while (index < trimmed.length) {
        val char = trimmed[index]
        when {
            char == '\\' && trimmed.getOrNull(index + 1) == '|' -> {
                cell.append('|')
                index += 1
            }
            char == '|' -> {
                cells += cell.toString().trim()
                cell.clear()
            }
            else -> cell.append(char)
        }
        index += 1
    }
    // 末尾の縦棒で閉じた行は、その後ろに空のセルを足さない。
    if (cell.isNotBlank() || !trimmed.endsWith('|') || trimmed.endsWith("\\|")) cells += cell.toString().trim()
    return cells
}

private fun List<String>.fitTo(size: Int): List<String> =
    take(size) + List((size - this.size).coerceAtLeast(0)) { "" }

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
