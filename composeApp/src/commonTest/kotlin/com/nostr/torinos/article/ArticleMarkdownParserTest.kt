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

    @Test
    fun parseMarkdownBlocks_parsesGfmTableWithAlignments() {
        val blocks = parseMarkdownBlocks(
            """
            before
            | 項目 | 内容 | 数 | 備考 |
            |------|:-----|:--:|---:|
            | 運用者 | **1人** | 1 | なし |

            after
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("before"),
                MarkdownBlock.Table(
                    alignments = listOf(
                        MarkdownTableAlignment.None,
                        MarkdownTableAlignment.Left,
                        MarkdownTableAlignment.Center,
                        MarkdownTableAlignment.Right,
                    ),
                    header = listOf("項目", "内容", "数", "備考"),
                    rows = listOf(listOf("運用者", "**1人**", "1", "なし")),
                ),
                MarkdownBlock.Blank,
                MarkdownBlock.Paragraph("after"),
            ),
            blocks,
        )
    }

    @Test
    fun parseMarkdownBlocks_tableWithoutOuterPipesAndEscapedPipe() {
        val blocks = parseMarkdownBlocks("a | b\\|c\n--- | ---\nx | `y \\| z`")

        assertEquals(
            listOf(
                MarkdownBlock.Table(
                    alignments = listOf(MarkdownTableAlignment.None, MarkdownTableAlignment.None),
                    header = listOf("a", "b|c"),
                    rows = listOf(listOf("x", "`y | z`")),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun parseMarkdownBlocks_padsShortRowsAndDropsExtraCells() {
        val blocks = parseMarkdownBlocks("| a | b |\n|---|---|\n| 1 |\n| 1 | 2 | 3 |")

        assertEquals(
            listOf(
                MarkdownBlock.Table(
                    alignments = listOf(MarkdownTableAlignment.None, MarkdownTableAlignment.None),
                    header = listOf("a", "b"),
                    rows = listOf(listOf("1", ""), listOf("1", "2")),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun parseMarkdownBlocks_tableEndsAtBlankLineOrOtherBlock() {
        val blocks = parseMarkdownBlocks("| a |\n|---|\n| 1 |\nplain row\n> quote\n| 2 |\n\n| 3 |")

        assertEquals(
            listOf(
                MarkdownBlock.Table(
                    alignments = listOf(MarkdownTableAlignment.None),
                    header = listOf("a"),
                    rows = listOf(listOf("1"), listOf("plain row")),
                ),
                MarkdownBlock.Quote("quote"),
                MarkdownBlock.Paragraph("| 2 |"),
                MarkdownBlock.Blank,
                MarkdownBlock.Paragraph("| 3 |"),
            ),
            blocks,
        )
    }

    @Test
    fun parseMarkdownBlocks_keepsNonTablePipesAsParagraphs() {
        val blocks = parseMarkdownBlocks(
            listOf(
                // 区切り行がない
                "| a | b |",
                "| 1 | 2 |",
                "",
                // 区切り行の列数が見出し行と違う
                "| a | b |",
                "|---|",
                "",
                // 縦棒のない見出しと水平線
                "title",
                "---",
                "",
                // 区切り行に - がない
                "| a |",
                "| : |",
            ).joinToString("\n"),
        )

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("| a | b |"),
                MarkdownBlock.Paragraph("| 1 | 2 |"),
                MarkdownBlock.Blank,
                MarkdownBlock.Paragraph("| a | b |"),
                MarkdownBlock.Paragraph("|---|"),
                MarkdownBlock.Blank,
                MarkdownBlock.Paragraph("title"),
                MarkdownBlock.Paragraph("---"),
                MarkdownBlock.Blank,
                MarkdownBlock.Paragraph("| a |"),
                MarkdownBlock.Paragraph("| : |"),
            ),
            blocks,
        )
    }

    @Test
    fun parseMarkdownBlocks_tableInsideFenceStaysCode() {
        val blocks = parseMarkdownBlocks("```\n| a |\n|---|\n```")

        assertEquals(listOf(MarkdownBlock.Code("| a |\n|---|")), blocks)
    }

    @Test
    fun parseArticleMarkdown_collectsQuotesFromTableCells() {
        val first = noteId(1)
        val second = noteId(2)

        val parsed = parseArticleMarkdown(
            content = "| 投稿 | メモ |\n|---|---|\n| nostr:${note(1)} | 見る nostr:${note(2)} |",
            articleQuoteIds = emptyList(),
        )

        assertEquals(
            listOf(
                ArticleMarkdownSection(
                    MarkdownBlock.Table(
                        alignments = listOf(MarkdownTableAlignment.None, MarkdownTableAlignment.None),
                        header = listOf("投稿", "メモ"),
                        rows = listOf(listOf("", "見る")),
                    ),
                    listOf(first, second),
                ),
            ),
            parsed.sections,
        )
    }

    private fun noteBytes(seed: Int) = ByteArray(32) { seed.toByte() }

    private fun note(seed: Int): String = Bech32.encode("note", noteBytes(seed))

    private fun noteId(seed: Int): String = noteBytes(seed).toHex()
}
