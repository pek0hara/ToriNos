package com.nostr.torinos.model

import kotlin.test.*

class ArticlePreviewTest {
    private val nevent = encodeNevent("11".repeat(32), listOf("wss://relay.example"), "aa".repeat(32))
    private fun article(content: String, topics: List<String> = emptyList(), summary: String? = null) = ArticleItem(
        NostrEvent("ff".repeat(32), "aa".repeat(32), 1, NIP23_ARTICLE_KIND, emptyList(), content, ""),
        ArticleMeta("d", "title", summary, null, null, topics),
    )

    @Test fun previewDropsReferencesMarkersCodeAndTheMatometrTag() {
        val content = "# 見出し\n\n**大事**な[リンク](https://example.com)\n\nnostr:$nevent\n\n```\ncode\n```\n> 引用\n- 項目\n\n#matometr"
        assertEquals("見出し 大事なリンク 引用 項目", markdownPreview(content))
    }

    @Test fun hashtagsInProseAreKept() {
        assertEquals("#nostr 楽しい", markdownPreview("#nostr 楽しい"))
    }

    @Test fun quoteOnlyMatometrSaysHowManyPostsItHolds() {
        val other = encodeNevent("22".repeat(32))
        assertEquals("2件の投稿をまとめた記事", article("nostr:$nevent\n\nnostr:$other\n\n#matometr", listOf("matometr")).displaySummary)
        assertEquals("", article("nostr:$nevent").displaySummary)
        assertEquals("要約", article("nostr:$nevent", listOf("matometr"), summary = "要約").displaySummary)
    }
}
