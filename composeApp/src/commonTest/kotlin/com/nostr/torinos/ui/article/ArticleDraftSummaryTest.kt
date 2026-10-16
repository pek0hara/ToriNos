package com.nostr.torinos.ui.article

import com.nostr.torinos.article.ArticleDraft
import com.nostr.torinos.article.ArticleDraftMode
import com.nostr.torinos.article.MatometrReference
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArticleDraftSummaryTest {
    private val owner = "aa".repeat(32)

    @Test fun previewStripsMarkdownAndNostrReferences() {
        val draft = ArticleDraft(ownerPubkey = owner, mode = ArticleDraftMode.MatometrMarkdown,
            bodyMarkdown = "## 見出し\n\n**今日**は[きのこ](https://example.com)狩り\n\nnostr:nevent1qqsabc\n\n```\ncode\n```\n- 項目")
        assertEquals("見出し 今日はきのこ狩り code 項目", articleDraftPreview(draft))
    }

    @Test fun previewKeepsInlineDashesAndSingleUnderscores() {
        assertEquals("A - B snake_case 強調", articleDraftPreview(ArticleDraft(ownerPubkey = owner, bodyMarkdown = "A - B snake_case __強調__")))
    }

    @Test fun summaryWinsAndStructuredFallsBackToFirstCommentary() {
        assertEquals("概要", articleDraftPreview(ArticleDraft(ownerPubkey = owner, summary = "概要", bodyMarkdown = "本文")))
        val structured = ArticleDraft(ownerPubkey = owner, mode = ArticleDraftMode.MatometrStructured,
            references = listOf(MatometrReference("11".repeat(32)), MatometrReference("22".repeat(32), commentaryMarkdown = "最初の説明")))
        assertEquals("最初の説明", articleDraftPreview(structured))
        assertEquals("", articleDraftPreview(ArticleDraft(ownerPubkey = owner)))
    }

    @Test fun longPreviewIsTruncated() {
        val preview = articleDraftPreview(ArticleDraft(ownerPubkey = owner, bodyMarkdown = "あ".repeat(200)), maxLength = 10)
        assertEquals("あ".repeat(10) + "…", preview)
    }

    @Test fun summaryShowsKindQuoteCountAndTopicsWithoutMatometrTag() {
        val summary = ArticleDraft(ownerPubkey = owner, mode = ArticleDraftMode.MatometrStructured, topicsInput = "nostr matometr",
            references = listOf(MatometrReference("11".repeat(32)), MatometrReference("22".repeat(32)))).toSummary()
        assertEquals("無題の記事", summary.title)
        assertEquals("まとめ", summary.kindLabel)
        assertEquals(2, summary.quoteCount)
        assertEquals(listOf("nostr"), summary.topics)
        assertNull(ArticleDraft(ownerPubkey = owner).toSummary().quoteCount)
    }

    @Test fun updatedAtUsesTimeTodayThenRelativeDaysThenDate() {
        val tz = TimeZone.UTC
        val now = 1_791_331_200L + 15 * 3600 // 2026-10-07 15:00 UTC
        assertEquals("09:30", formatDraftUpdatedAt(now - 5 * 3600 - 1800, now, tz))
        assertEquals("昨日", formatDraftUpdatedAt(now - 86_400, now, tz))
        assertEquals("6日前", formatDraftUpdatedAt(now - 6 * 86_400, now, tz))
        assertTrue(formatDraftUpdatedAt(now - 7 * 86_400, now, tz).startsWith("09/30"))
    }

    @Test fun editOfAPublishedArticleIsMarkedAsAnUpdateAndKeepsItsKind() {
        val edit = ArticleDraft(ownerPubkey = owner, articleIdentifier = "published", baseEventId = "ef".repeat(32),
            originalPublishedAt = 1_790_000_000, bodyMarkdown = "本文").toSummary()
        assertTrue(edit.isUpdate)
        assertEquals("通常記事", edit.kindLabel)
        assertEquals(1_790_000_000, edit.publishedAt)
        val newDraft = ArticleDraft(ownerPubkey = owner, articleIdentifier = "new", bodyMarkdown = "本文").toSummary()
        assertFalse(newDraft.isUpdate)
        assertNull(newDraft.publishedAt)
    }

    @Test fun draftWithoutBaseIsAnUpdateOnceItsAddressIsKnownToBePublished() {
        val foreign = ArticleDraft(ownerPubkey = owner, articleIdentifier = "published", mode = ArticleDraftMode.MatometrMarkdown,
            bodyMarkdown = "本文")
        assertFalse(foreign.toSummary().isUpdate)
        val summary = foreign.toSummary(publishedIdentifiers = setOf("published"))
        assertTrue(summary.isUpdate)
        assertEquals("まとめ", summary.kindLabel)
    }

    @Test fun publishedDateIsMonthAndDay() {
        assertEquals("10/1に公開済み", formatDraftPublishedAt(1_790_812_800, TimeZone.UTC)) // 2026-10-01 00:00 UTC
    }
}
