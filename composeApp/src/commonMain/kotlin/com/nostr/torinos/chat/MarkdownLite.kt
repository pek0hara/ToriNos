package com.nostr.torinos.chat

/**
 * チャットのメッセージ向けの小さな Markdown（Buzz の本文）。段落・引用・コードブロック・画像と、
 * 強調・斜体・コード・リンク・`||ネタバレ||` だけを扱う。それ以外は文字のまま残す。
 */
sealed interface MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Quote(val inlines: List<MdInline>) : MdBlock
    data class CodeBlock(val text: String) : MdBlock
    data class Image(val alt: String, val url: String) : MdBlock
}

sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Bold(val text: String) : MdInline
    data class Italic(val text: String) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val text: String, val url: String) : MdInline
    data class Spoiler(val text: String) : MdInline
}

private val HEADING_PATTERN = Regex("^#{1,6} .*")

private const val ESCAPABLE = "\\`*_{}[]()#+-.!|<>&~"

fun parseMarkdownLite(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    val quote = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) blocks += paragraphBlocks(paragraph.joinToString("\n"))
        paragraph.clear()
    }
    fun flushQuote() {
        if (quote.isNotEmpty()) blocks += MdBlock.Quote(parseInlines(quote.joinToString("\n")).filterNot { it is ImageMarker }.map { it as MdInline })
        quote.clear()
    }

    val lines = source.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flushParagraph(); flushQuote()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code += lines[i]
                    i++
                }
                blocks += MdBlock.CodeBlock(code.joinToString("\n"))
            }
            HEADING_PATTERN.matches(line) -> {
                flushParagraph(); flushQuote()
                val level = line.takeWhile { it == '#' }.length
                blocks += MdBlock.Heading(level, parseInlines(line.drop(level).trim()).filterNot { it is ImageMarker }.map { it as MdInline })
            }
            line.startsWith(">") -> {
                flushParagraph()
                quote += line.removePrefix(">").removePrefix(" ")
            }
            line.isBlank() -> {
                flushParagraph(); flushQuote()
            }
            else -> {
                flushQuote()
                paragraph += line
            }
        }
        i++
    }
    flushParagraph(); flushQuote()
    return blocks
}

/** 本文中の画像の URL（表示前に読み込みの準備をするため）。 */
fun markdownImageUrls(blocks: List<MdBlock>): List<String> = blocks.filterIsInstance<MdBlock.Image>().map { it.url }

/** 段落の中の画像は、前後で段落を分けて画像の段にする。 */
private fun paragraphBlocks(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val current = mutableListOf<MdInline>()
    fun flush() {
        val trimmed = trimInlines(current)
        if (trimmed.isNotEmpty()) out += MdBlock.Paragraph(trimmed)
        current.clear()
    }
    parseInlines(text).forEach { item ->
        if (item is ImageMarker) {
            flush()
            out += MdBlock.Image(item.alt, item.url)
        } else {
            current += item as MdInline
        }
    }
    flush()
    return out
}

private fun trimInlines(inlines: List<MdInline>): List<MdInline> {
    if (inlines.isEmpty()) return inlines
    val result = inlines.toMutableList()
    (result.first() as? MdInline.Text)?.let { first ->
        val t = first.text.trimStart()
        if (t.isEmpty()) result.removeAt(0) else result[0] = MdInline.Text(t)
    }
    (result.lastOrNull() as? MdInline.Text)?.let { last ->
        val t = last.text.trimEnd()
        if (t.isEmpty()) result.removeAt(result.lastIndex) else result[result.lastIndex] = MdInline.Text(t)
    }
    return result
}

private data class ImageMarker(val alt: String, val url: String)

/** 文字の並びを強調などに分ける。戻り値は [MdInline] か [ImageMarker]。 */
private fun parseInlines(text: String): List<Any> {
    val out = mutableListOf<Any>()
    val buffer = StringBuilder()
    fun flushText() {
        if (buffer.isEmpty()) return
        val last = out.lastOrNull()
        if (last is MdInline.Text) out[out.lastIndex] = MdInline.Text(last.text + buffer) else out += MdInline.Text(buffer.toString())
        buffer.clear()
    }
    fun emit(item: Any) {
        flushText()
        out += item
    }

    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
            buffer.append(text[i + 1]); i += 2; continue
        }
        if (text.startsWith("![", i)) {
            parseLinkAt(text, i + 1)?.let { (label, url, end) -> emit(ImageMarker(label, url)); i = end; return@let }
                ?: run { buffer.append(c); i++ }
            continue
        }
        if (c == '[') {
            val link = parseLinkAt(text, i)
            if (link != null) {
                emit(MdInline.Link(link.first, link.second)); i = link.third
            } else {
                buffer.append(c); i++
            }
            continue
        }
        val span = when {
            text.startsWith("**", i) -> delimited(text, i, "**")?.let { (inner, end) -> MdInline.Bold(unescape(inner)) to end }
            text.startsWith("||", i) -> delimited(text, i, "||")?.let { (inner, end) -> MdInline.Spoiler(unescape(inner)) to end }
            c == '`' -> delimited(text, i, "`")?.let { (inner, end) -> MdInline.Code(inner) to end }
            c == '*' -> delimited(text, i, "*", requireTight = true)?.let { (inner, end) -> MdInline.Italic(unescape(inner)) to end }
            // `_` は単語の途中（my_var_name や URL）では斜体にしない。
            c == '_' && !text.getOrNull(i - 1).isWordChar() ->
                delimited(text, i, "_", requireTight = true)
                    ?.takeIf { (_, end) -> !text.getOrNull(end).isWordChar() }
                    ?.let { (inner, end) -> MdInline.Italic(unescape(inner)) to end }
            else -> null
        }
        if (span != null) {
            emit(span.first); i = span.second
        } else {
            buffer.append(c); i++
        }
    }
    flushText()
    return out
}

private fun Char?.isWordChar(): Boolean = this != null && (isLetterOrDigit() || this == '_')

/** [start] の区切り記号から、閉じる記号までの中身と、閉じた後の位置。閉じなければ null。 */
private fun delimited(text: String, start: Int, mark: String, requireTight: Boolean = false): Pair<String, Int>? {
    val from = start + mark.length
    if (from >= text.length) return null
    if (requireTight && text[from].isWhitespace()) return null
    var j = from
    while (j < text.length) {
        if (text[j] == '\\' && j + 1 < text.length) { j += 2; continue }
        if (text.startsWith(mark, j) && j > from) {
            if (requireTight && text[j - 1].isWhitespace()) return null
            return text.substring(from, j) to (j + mark.length)
        }
        if (text[j] == '\n' && mark.length == 1) return null
        j++
    }
    return null
}

/** `[label](url)` または `[label](<url>)`。（ラベル, URL, 次の位置）。 */
private fun parseLinkAt(text: String, open: Int): Triple<String, String, Int>? {
    if (text.getOrNull(open) != '[') return null
    var j = open + 1
    while (j < text.length && text[j] != ']') {
        if (text[j] == '\\' && j + 1 < text.length) j++
        if (text[j] == '\n') return null
        j++
    }
    if (j >= text.length || text.getOrNull(j + 1) != '(') return null
    val label = unescape(text.substring(open + 1, j))
    var k = j + 2
    val url: String
    if (text.getOrNull(k) == '<') {
        val close = text.indexOf(">)", k)
        if (close < 0) return null
        url = text.substring(k + 1, close)
        k = close + 2
    } else {
        val close = text.indexOf(')', k)
        if (close < 0) return null
        url = text.substring(k, close)
        if (url.any { it.isWhitespace() }) return null
        k = close + 1
    }
    if (url.isBlank()) return null
    return Triple(label, url, k)
}

private fun unescape(text: String): String = buildString {
    var i = 0
    while (i < text.length) {
        if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
            append(text[i + 1]); i += 2
        } else {
            append(text[i]); i++
        }
    }
}
