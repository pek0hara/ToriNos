package com.nostr.torinos.article

import com.nostr.torinos.crypto.Bech32
import com.nostr.torinos.crypto.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

class ArticleMarkdownParserTest {
    @Test
    fun parseMarkdownBlocks_classifiesLinePrefixes() {
        val blocks = parseMarkdownBlocks(
            """
            # H1
            ## H2
            ### H3
            #### H4
            > quoted
            - dash item
            * star item
                indented code
            plain text
            ![alt](https://example.com/a.png)
            ![alt](http://example.com/a.png)
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "H1"),
                MarkdownBlock.Heading(2, "H2"),
                MarkdownBlock.Heading(3, "H3"),
                MarkdownBlock.Paragraph("#### H4"),
                MarkdownBlock.Quote("quoted"),
                MarkdownBlock.ListItem("dash item"),
                MarkdownBlock.ListItem("star item"),
                MarkdownBlock.Code("indented code"),
                MarkdownBlock.Paragraph("plain text"),
                MarkdownBlock.Image(alt = "alt", url = "https://example.com/a.png"),
                MarkdownBlock.Paragraph("![alt](http://example.com/a.png)"),
            ),
            blocks,
        )
    }

    @Test
    fun parseMarkdownBlocks_keepsFencedCodeRawAndBlankLines() {
        val blocks = parseMarkdownBlocks("before\n\n```kotlin\n  # not heading\n```\nafter")

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("before"),
                MarkdownBlock.Blank,
                MarkdownBlock.Code("  # not heading"),
                MarkdownBlock.Paragraph("after"),
            ),
            blocks,
        )
    }

    @Test
    fun parseMarkdownBlocks_closesUnterminatedFenceAtEnd() {
        assertEquals(
            listOf(MarkdownBlock.Code("line1\nline2")),
            parseMarkdownBlocks("```\nline1\nline2"),
        )
    }

    @Test
    fun parseMarkdownInline_parsesCodeBoldItalicAndLinks() {
        assertEquals(
            listOf(
                MarkdownInline.Text("a "),
                MarkdownInline.Code("x*y"),
                MarkdownInline.Text(" "),
                MarkdownInline.Bold(listOf(MarkdownInline.Text("b"))),
                MarkdownInline.Text(" "),
                MarkdownInline.Bold(listOf(MarkdownInline.Text("u"))),
                MarkdownInline.Text(" "),
                MarkdownInline.Italic(listOf(MarkdownInline.Text("i"))),
                MarkdownInline.Text(" "),
                MarkdownInline.Italic(listOf(MarkdownInline.Text("j"))),
                MarkdownInline.Text(" "),
                MarkdownInline.Link(
                    url = "https://example.com",
                    children = listOf(MarkdownInline.Bold(listOf(MarkdownInline.Text("link")))),
                ),
            ),
            parseMarkdownInline("a `x*y` **b** __u__ *i* _j_ [**link**](https://example.com)"),
        )
    }

    @Test
    fun parseMarkdownInline_leavesUnclosedMarkersAsText() {
        assertEquals(
            listOf(MarkdownInline.Text("`a **b [c](d")),
            parseMarkdownInline("`a **b [c](d"),
        )
    }

    @Test
    fun parseMarkdownInline_treatsEmptyEmphasisAsText() {
        assertEquals(listOf(MarkdownInline.Text("**")), parseMarkdownInline("**"))
        assertEquals(
            listOf(MarkdownInline.Text("**"), MarkdownInline.Italic(listOf(MarkdownInline.Text("a")))),
            parseMarkdownInline("***a*"),
        )
    }

    @Test
    fun parseArticleMarkdown_placesQuotesAfterReferencingBlockAndStripsUris() {
        val first = noteId(1)
        val second = noteId(2)
        val tagOnly = "f".repeat(64)

        val parsed = parseArticleMarkdown(
            content = "intro nostr:${note(1)}\nnostr:${note(2)}\n```\nnostr:${note(1)}\n```",
            articleQuoteIds = listOf(first, second, tagOnly),
        )

        assertEquals(
            listOf(
                ArticleMarkdownSection(MarkdownBlock.Paragraph("intro"), listOf(first)),
                ArticleMarkdownSection(block = null, quoteIds = listOf(second)),
                ArticleMarkdownSection(MarkdownBlock.Code("nostr:${note(1)}"), emptyList()),
            ),
            parsed.sections,
        )
        assertEquals(listOf(tagOnly), parsed.trailingQuoteIds)
    }

    private fun noteBytes(seed: Int) = ByteArray(32) { seed.toByte() }

    private fun note(seed: Int): String = Bech32.encode("note", noteBytes(seed))

    private fun noteId(seed: Int): String = noteBytes(seed).toHex()
}
