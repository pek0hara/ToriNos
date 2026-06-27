package com.nostr.torinos.ui.article

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
}
