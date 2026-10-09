package com.nostr.torinos.ui.article

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleEditorViewModelTest {
    @Test
    fun parseArticleTopics_removesPrefixesDuplicatesAndEmptyValues() {
        assertEquals(
            listOf("nostr", "技術", "日記"),
            parseArticleTopics(" #nostr, 技術  nostr、#日記 "),
        )
    }

    @Test
    fun buildArticleTags_omitsBlankOptionalTags() {
        assertEquals(
            listOf(
                listOf("d", "article-id"),
                listOf("title", "タイトル"),
                listOf("published_at", "123"),
                listOf("t", "nostr"),
                listOf("client", "ToriNos"),
            ),
            buildArticleTags(
                identifier = "article-id",
                title = " タイトル ",
                summary = " ",
                coverImageUrl = "",
                topicsInput = "#nostr",
                publishedAt = 123,
            ),
        )
    }

    @Test
    fun containsHtml_detectsOpeningClosingAndSelfClosingTags() {
        assertTrue(containsHtml("<div>本文</div>"))
        assertTrue(containsHtml("前文<br />後文"))
        assertTrue(containsHtml("<script>alert('x')</script>"))
    }

    @Test
    fun containsHtml_allowsMarkdownAndComparisonSymbols() {
        assertFalse(containsHtml("# 見出し\n\n**本文**"))
        assertFalse(containsHtml("1 < 2 かつ 3 > 2"))
        assertFalse(containsHtml("https://example.com/?a=1&b=2"))
    }

    @Test
    fun articleEditorState_hasUnsavedChangesForAnyInput() {
        assertFalse(ArticleEditorState().hasUnsavedChanges)
        assertTrue(ArticleEditorState(title = "記事").hasUnsavedChanges)
        assertTrue(ArticleEditorState(content = "本文").hasUnsavedChanges)
        assertTrue(ArticleEditorState(coverImageUrl = "https://example.com/image.jpg").hasUnsavedChanges)
    }

    @Test
    fun articleEditorState_editingComparesAgainstInitialValues() {
        val editing = ArticleEditorState(
            title = "タイトル",
            summary = "概要",
            content = "本文",
            coverImageUrl = "https://example.com/image.jpg",
            topicsInput = "#nostr",
            initialTitle = "タイトル",
            initialSummary = "概要",
            initialContent = "本文",
            initialCoverImageUrl = "https://example.com/image.jpg",
            initialTopicsInput = "#nostr",
            editingArticle = EditingArticle(
                pubkey = "pubkey",
                identifier = "article-id",
                publishedAt = 123,
            ),
        )

        assertFalse(editing.hasUnsavedChanges)
        assertTrue(editing.copy(content = "更新した本文").hasUnsavedChanges)
    }

    @Test
    fun nextArticleSaveDelay_waitsForAPauseButNotPastTheMaximum() {
        assertEquals(10_000, nextArticleSaveDelay(0, 10_000, 60_000))
        assertEquals(5_000, nextArticleSaveDelay(55_000, 10_000, 60_000))
        assertEquals(0, nextArticleSaveDelay(90_000, 10_000, 60_000))
    }

    @Test
    fun wrapMarkdownSelection_selectsPlaceholderOrKeepsSelectedText() {
        assertEquals("ab**太字**c" to TextRange(4, 6), wrapMarkdownSelection("abc", TextRange(2), "**", "**", "太字"))
        assertEquals("a**bc**" to TextRange(3, 5), wrapMarkdownSelection("abc", TextRange(1, 3), "**", "**", "太字"))
    }

    @Test
    fun toggleMarkdownLinePrefix_editsTheCaretLineStart() {
        assertEquals("one\n## two" to TextRange(9), toggleMarkdownLinePrefix("one\ntwo", TextRange(6), "## "))
        assertEquals("one\ntwo" to TextRange(6), toggleMarkdownLinePrefix("one\n## two", TextRange(9), "## "))
        assertEquals("## title" to TextRange(5), toggleMarkdownLinePrefix("### title", TextRange(6), "## "))
        assertEquals("## " to TextRange(3), toggleMarkdownLinePrefix("", TextRange(0), "## "))
    }
}
