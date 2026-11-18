package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownLiteTest {
    @Test
    fun inlineStylesBecomeSpans() {
        assertEquals(
            listOf(
                MdBlock.Paragraph(
                    listOf(
                        MdInline.Text("今日は "),
                        MdInline.Bold("15時"),
                        MdInline.Text(" に "),
                        MdInline.Italic("集合"),
                        MdInline.Text(" "),
                        MdInline.Code("make"),
                    ),
                ),
            ),
            parseMarkdownLite("今日は **15時** に *集合* `make`"),
        )
    }

    @Test
    fun linksAndSpoilers() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdInline.Link("ここ", "https://a.example"), MdInline.Text(" "), MdInline.Spoiler("ネタバレ")))),
            parseMarkdownLite("[ここ](https://a.example) ||ネタバレ||"),
        )
    }

    @Test
    fun imagesBecomeTheirOwnBlocks() {
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(MdInline.Text("見て"))),
                MdBlock.Image("photo.png", "https://h.example/media/abc.png"),
            ),
            parseMarkdownLite("見て\n\n![photo.png](<https://h.example/media/abc.png>)"),
        )
    }

    @Test
    fun attachmentLinkWithoutBangIsALink() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdInline.Link("doc.pdf", "https://h.example/media/x.pdf")))),
            parseMarkdownLite("[doc.pdf](<https://h.example/media/x.pdf>)"),
        )
    }

    @Test
    fun quotesAndCodeBlocks() {
        assertEquals(
            listOf(
                MdBlock.Quote(listOf(MdInline.Text("引用"))),
                MdBlock.CodeBlock("val x = 1"),
            ),
            parseMarkdownLite("> 引用\n```kotlin\nval x = 1\n```"),
        )
    }

    @Test
    fun escapesAndUnclosedMarkersStayAsText() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdInline.Text("2 * 3 = 6 と [x]")))),
            parseMarkdownLite("2 \\* 3 = 6 と \\[x]"),
        )
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdInline.Text("**閉じない")))),
            parseMarkdownLite("**閉じない"),
        )
    }

    @Test
    fun lineBreaksInsideParagraphAreKept() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdInline.Text("1行目\n2行目")))),
            parseMarkdownLite("1行目\n2行目"),
        )
    }

    @Test
    fun imageUrlsAreCollected() {
        assertEquals(
            listOf("https://h.example/media/a.png"),
            markdownImageUrls(parseMarkdownLite("x ![a](<https://h.example/media/a.png>)")),
        )
    }

    @Test
    fun headingsAreTheirOwnBlocks() {
        assertEquals(
            listOf(MdBlock.Heading(1, listOf(MdInline.Text("利用規約"))), MdBlock.Paragraph(listOf(MdInline.Bold("本文")))),
            parseMarkdownLite("# 利用規約\n**本文**"),
        )
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdInline.Text("#タグ")))), parseMarkdownLite("#タグ"))
    }

    @Test
    fun underscoresInsideWordsAndUrlsAreKept() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdInline.Text("see my_var_name と https://example.com/a_b_c")))),
            parseMarkdownLite("see my_var_name と https://example.com/a_b_c"),
        )
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdInline.Text("これは "), MdInline.Italic("斜体"), MdInline.Text(" です")))),
            parseMarkdownLite("これは _斜体_ です"),
        )
    }
}
