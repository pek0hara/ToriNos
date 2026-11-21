package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MarkdownCollapseTest {
    private fun collapse(source: String, maxChars: Int = 10, maxLines: Int = 3, emojis: Set<String> = emptySet()) =
        collapseMarkdown(parseMarkdownLite(source), maxChars, maxLines, emojis)

    @Test
    fun shortPostIsLeftAsIs() {
        val blocks = parseMarkdownLite("短い\n\n**本文**")
        val result = collapseMarkdown(blocks, maxChars = 10, maxLines = 3, emojiShortcodes = emptySet())
        assertFalse(result.truncated)
        assertSame(blocks, result.blocks)
    }

    @Test
    fun exactlyAtTheLimitIsNotTruncated() {
        assertFalse(collapse("0123456789").truncated)
    }

    @Test
    fun longTextIsCutAtTheCharacterLimit() {
        val result = collapse("0123456789abc")
        assertTrue(result.truncated)
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdInline.Text("0123456789…")))), result.blocks)
    }

    @Test
    fun characterBudgetIsSharedAcrossBlocksAndStyles() {
        val result = collapse("abcd\n\n**efgh** ijkl")
        assertTrue(result.truncated)
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(MdInline.Text("abcd"))),
                MdBlock.Paragraph(listOf(MdInline.Bold("efgh"), MdInline.Text(" i…"))),
            ),
            result.blocks,
        )
    }

    @Test
    fun tooManyLinesAreCutAtTheLineLimit() {
        val result = collapse("a\nb\nc\nd", maxChars = 100)
        assertTrue(result.truncated)
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdInline.Text("a\nb\nc…")))), result.blocks)
    }

    @Test
    fun eachBlockStartsANewLine() {
        val result = collapse("a\n\nb\n\nc\n\nd", maxChars = 100)
        assertTrue(result.truncated)
        assertEquals(
            listOf("a", "b", "c").map { MdBlock.Paragraph(listOf(MdInline.Text(it))) },
            result.blocks,
        )
    }

    @Test
    fun linksAreNeverCutInTheMiddle() {
        val result = collapse("abcdefgh https://example.com/long/path tail")
        assertTrue(result.truncated)
        assertEquals(
            listOf(
                MdBlock.Paragraph(
                    listOf(
                        MdInline.Text("abcdefgh "),
                        MdInline.Link("https://example.com/long/path", "https://example.com/long/path"),
                        MdInline.Text("…"),
                    ),
                ),
            ),
            result.blocks,
        )
    }

    @Test
    fun linkAfterTheBudgetRunsOutIsDropped() {
        val result = collapse("0123456789[ここ](https://a.example)")
        assertTrue(result.truncated)
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdInline.Text("0123456789…")))), result.blocks)
    }

    @Test
    fun customEmojiCountsAsOneCharacterAndIsNotSplit() {
        val result = collapse("123456789:smile::smile:", emojis = setOf("smile"))
        assertTrue(result.truncated)
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdInline.Text("123456789:smile:…")))), result.blocks)
    }

    @Test
    fun imagesDoNotUseTheBudgetButAreHiddenAfterTheCut() {
        val before = MdBlock.Image("a", "https://h.example/a.png")
        val after = MdBlock.Image("b", "https://h.example/b.png")
        val blocks = listOf(before, MdBlock.Paragraph(listOf(MdInline.Text("0123456789abc"))), after)
        val result = collapseMarkdown(blocks, maxChars = 10, maxLines = 3, emojiShortcodes = emptySet())
        assertTrue(result.truncated)
        assertEquals(listOf(before, MdBlock.Paragraph(listOf(MdInline.Text("0123456789…")))), result.blocks)
    }

    @Test
    fun imagesOnlyPostIsNotCollapsed() {
        val blocks = List(5) { MdBlock.Image("$it", "https://h.example/$it.png") }
        assertFalse(collapseMarkdown(blocks, maxChars = 10, maxLines = 3, emojiShortcodes = emptySet()).truncated)
    }

    @Test
    fun codeBlockIsCutByLines() {
        val result = collapse("```\nl1\nl2\nl3\nl4\n```", maxChars = 100)
        assertTrue(result.truncated)
        assertEquals(listOf(MdBlock.CodeBlock("l1\nl2\nl3…")), result.blocks)
    }

    @Test
    fun trailingSpaceBeforeTheCutIsTrimmed() {
        val result = collapse("012345678 abc")
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdInline.Text("012345678…")))), result.blocks)
    }

    @Test
    fun bareUrlIsShownShortened() {
        assertEquals("example.com/a", MdInline.Link("https://www.example.com/a/", "https://www.example.com/a/").displayText())
    }

    @Test
    fun labelledLinkKeepsItsLabel() {
        assertEquals("ここ", MdInline.Link("ここ", "https://www.example.com/a/").displayText())
    }

    @Test
    fun buzzLinkIsNotShortened() {
        assertEquals("buzz://x/y", MdInline.Link("buzz://x/y", "buzz://x/y").displayText())
    }
}
