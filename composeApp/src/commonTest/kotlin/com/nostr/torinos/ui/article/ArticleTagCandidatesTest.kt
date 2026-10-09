package com.nostr.torinos.ui.article

import com.nostr.torinos.model.ArticleItem
import com.nostr.torinos.model.ArticleMeta
import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class ArticleTagCandidatesTest {
    private fun article(vararg topics: String) = ArticleItem(
        NostrEvent("ff".repeat(32), "aa".repeat(32), 1, NIP23_ARTICLE_KIND, emptyList(), "", ""),
        ArticleMeta("d", "t", null, null, null, topics.toList()),
    )

    @Test fun matometrComesFirstThenRecentThenFrequent() {
        val articles = listOf(article("nostr", "日記"), article("Nostr", "matometr"), article("nostr", "技術"), article("技術"))
        assertEquals(listOf("matometr", "料理", "NOSTR", "技術", "日記"), articleTagCandidates(articles, recent = listOf("料理", "NOSTR")))
    }

    @Test fun frequentTagsAreLimited() {
        val articles = (1..8).map { article("tag$it") }
        assertEquals(listOf("matometr", "tag1", "tag2"), articleTagCandidates(articles, recent = emptyList(), frequentLimit = 2))
    }
}
