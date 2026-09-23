package com.nostr.torinos.ui.article

import com.nostr.torinos.model.ArticleItem
import com.nostr.torinos.model.ArticleMeta
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.articleAddress
import com.nostr.torinos.model.latestArticleVersions
import com.nostr.torinos.model.toArticleAuthors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArticleDifferentialUpdateTest {
    @Test
    fun withUpsertedArticle_insertsNewAddressAtSortedPosition() {
        val existing = listOf(
            article(pubkey = "a", identifier = "1", createdAt = 300, publishedAt = 300),
            article(pubkey = "b", identifier = "1", createdAt = 100, publishedAt = 100),
        )
        val candidate = article(pubkey = "c", identifier = "1", createdAt = 200, publishedAt = 200)

        val result = existing.withUpsertedArticle(candidate)

        assertEquals(listOf(300L, 200L, 100L), result.map { it.sortTime })
        assertEquals(candidate, result[1])
    }

    @Test
    fun withUpsertedArticle_replacesOlderVersionOfSameAddress() {
        val original = article(pubkey = "a", identifier = "1", createdAt = 100, publishedAt = 100)
        val edited = article(pubkey = "a", identifier = "1", createdAt = 200, publishedAt = 200)
        val existing = listOf(
            article(pubkey = "b", identifier = "1", createdAt = 150, publishedAt = 150),
            original,
        )

        val result = existing.withUpsertedArticle(edited)

        assertEquals(2, result.size)
        assertEquals(edited, result.first())
    }

    @Test
    fun withUpsertedArticle_ignoresOlderOrEqualVersionOfSameAddress() {
        val current = article(pubkey = "a", identifier = "1", createdAt = 200, publishedAt = 200)
        val stale = article(pubkey = "a", identifier = "1", createdAt = 100, publishedAt = 100)
        val existing = listOf(current)

        val result = existing.withUpsertedArticle(stale)

        assertSame(existing, result)
    }

    @Test
    fun withUpsertedArticle_matchesFullRebuildOrdering() {
        val a1 = article(pubkey = "a", identifier = "1", createdAt = 100, publishedAt = 100)
        val a2 = article(pubkey = "a", identifier = "1", createdAt = 300, publishedAt = 300)
        val b1 = article(pubkey = "b", identifier = "1", createdAt = 200, publishedAt = 200)

        val incremental = emptyList<ArticleItem>()
            .withUpsertedArticle(a1)
            .withUpsertedArticle(b1)
            .withUpsertedArticle(a2)

        val fullRebuild = listOf(a1, b1, a2).latestArticleVersions()

        assertEquals(fullRebuild, incremental)
    }

    @Test
    fun withUpdatedAuthor_recomputesCountAndLatestForSinglePubkey() {
        val old = article(pubkey = "a", identifier = "1", createdAt = 100, publishedAt = 100)
        val updated = listOf(
            old.copy(event = old.event.copy(createdAt = 200), meta = old.meta.copy(publishedAt = 200)),
            article(pubkey = "a", identifier = "2", createdAt = 150, publishedAt = 150),
        )
        val authors = listOf(old).toArticleAuthors()

        val result = authors.withUpdatedAuthor("a", updated)

        assertEquals(1, result.size)
        assertEquals(2, result.first().articleCount)
        assertEquals(200L, result.first().latestArticle.sortTime)
    }

    @Test
    fun withUpdatedAuthor_removesAuthorWithNoRemainingArticles() {
        val a = article(pubkey = "a", identifier = "1", createdAt = 100, publishedAt = 100)
        val b = article(pubkey = "b", identifier = "1", createdAt = 200, publishedAt = 200)
        val authors = listOf(a, b).toArticleAuthors()

        val result = authors.withUpdatedAuthor("a", listOf(b))

        assertTrue(result.none { it.pubkey == "a" })
        assertEquals(listOf("b"), result.map { it.pubkey })
    }

    @Test
    fun withUpdatedAuthor_matchesFullRebuildOrdering() {
        val articles = listOf(
            article(pubkey = "a", identifier = "1", createdAt = 100, publishedAt = 100),
            article(pubkey = "b", identifier = "1", createdAt = 300, publishedAt = 300),
            article(pubkey = "a", identifier = "2", createdAt = 250, publishedAt = 250),
        )

        val incremental = emptyList<com.nostr.torinos.model.ArticleAuthorItem>()
            .withUpdatedAuthor("a", articles.filter { it.event.pubkey == "a" })
            .withUpdatedAuthor("b", articles)
            .withUpdatedAuthor("a", articles)

        val fullRebuild = articles.toArticleAuthors()

        assertEquals(fullRebuild, incremental)
    }

    private fun article(
        pubkey: String,
        identifier: String,
        createdAt: Long,
        publishedAt: Long?,
        profile: NostrProfile? = null,
    ): ArticleItem {
        val event = NostrEvent(
            id = "$pubkey-$identifier-$createdAt",
            pubkey = pubkey,
            createdAt = createdAt,
            kind = 30023,
            tags = emptyList(),
            content = "本文",
            sig = "sig",
        )
        val meta = ArticleMeta(
            identifier = identifier,
            title = "タイトル",
            summary = null,
            imageUrl = null,
            publishedAt = publishedAt,
            topics = emptyList(),
        )
        check(articleAddress(pubkey, identifier).isNotBlank())
        return ArticleItem(event = event, meta = meta, authorProfile = profile)
    }
}
