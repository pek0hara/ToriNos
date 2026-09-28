package com.nostr.torinos.article

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArticleEngagementSummaryTest {
    private val first = ArticleEngagementTarget(address = "30023:alice:one", eventId = "v-one")
    private val second = ArticleEngagementTarget(address = "30023:bob:two", eventId = "v-two")

    @AfterTest
    fun tearDown() {
        ArticleEngagementSummaryStore.clearForTest()
    }

    @Test
    fun reactionOptionForKey_restoresUnicodeAndCustomOptions() {
        val unicode = ReactionOption.Unicode("🔥")
        val custom = ReactionOption.Custom("party", "https://example.com/party.png")

        assertEquals(unicode, reactionOptionForKey(unicode.key))
        assertEquals(custom, reactionOptionForKey(custom.key))
        assertNull(reactionOptionForKey("like"))
    }

    @Test
    fun batchSummaryCountsPerArticleWithOwnReaction() {
        val events = listOf(
            reaction("r1", first.address, pubkey = "carol", content = "+"),
            reaction("r2", first.address, pubkey = "me", content = "🔥"),
            reaction("r2", first.address, pubkey = "me", content = "🔥"),
            reaction("r3", second.address, pubkey = "dave", content = "+"),
            comment("c1", first.address),
            comment("c2", first.address),
            event("n1", kind = 1, tags = listOf(listOf("a", second.address), listOf("e", "v-two", "", "root"))),
        )

        val summaries = summarizeArticleEngagementBatch(
            events,
            listOf(first, second),
            ownPubkey = "me",
            limit = 500,
            nowMillis = 42,
        )

        val one = summaries.getValue(first.address)
        assertEquals(2, one.reactionCount)
        assertEquals(2, one.commentCount)
        assertEquals(OwnArticleReaction.Emoji(ReactionOption.Unicode("🔥")), one.ownReaction)
        assertEquals(42, one.fetchedAtMillis)
        val two = summaries.getValue(second.address)
        assertEquals(1, two.reactionCount)
        assertEquals(1, two.commentCount)
        assertNull(two.ownReaction)
        assertFalse(two.isReactionLowerBound)
    }

    @Test
    fun batchSummaryMarksLowerBoundWhenLimitReached() {
        val events = (1..3).map { reaction("r$it", first.address, pubkey = "p$it", content = "+") } +
            reaction("mine", first.address, pubkey = "me", content = "+")

        val summary = summarizeArticleEngagementBatch(events, listOf(first), "me", limit = 3, nowMillis = 0)
            .getValue(first.address)

        assertTrue(summary.isReactionLowerBound)
        assertFalse(summary.isCommentLowerBound)
        assertEquals(OwnArticleReaction.Like, summary.ownReaction)
    }

    @Test
    fun batchSummaryIgnoresReactionsDeletedByTheirAuthor() {
        val events = listOf(
            reaction("mine", first.address, pubkey = "me", content = "+"),
            event("del", kind = 5, pubkey = "me", tags = listOf(listOf("e", "mine"))),
        )

        val summary = summarizeArticleEngagementBatch(events, listOf(first), "me", limit = 500, nowMillis = 0)
            .getValue(first.address)

        assertEquals(0, summary.reactionCount)
        assertNull(summary.ownReaction)
    }

    @Test
    fun listFiltersIncludeOwnReactionFilterOnlyWhenLoggedIn() {
        val addresses = listOf(first.address, second.address)

        val loggedIn = articleListEngagementFilters(addresses, ownPubkey = "me", limit = 500)
        val loggedOut = articleListEngagementFilters(addresses, ownPubkey = null, limit = 500)

        assertEquals(5, loggedIn.size)
        assertEquals(listOf("me"), loggedIn[3].authors)
        assertEquals(addresses, loggedIn[3].aTags)
        assertEquals(listOf(5), loggedIn[4].kinds)
        assertEquals(3, loggedOut.size)
    }

    @Test
    fun batchSelectionSkipsFreshAndInFlightAndChunks() {
        val targets = (1..5).map { ArticleEngagementTarget("30023:a:$it", "v$it") }

        val batches = selectArticleEngagementBatches(
            candidates = targets + targets.first(),
            isFresh = { it == "30023:a:2" },
            inFlight = setOf("30023:a:3"),
            batchSize = 2,
        )

        assertEquals(
            listOf(listOf("30023:a:1", "30023:a:4"), listOf("30023:a:5")),
            batches.map { batch -> batch.map { it.address } },
        )
    }

    @Test
    fun storeSeparatesAccountsAndExpiresEntries() {
        val summary = ArticleEngagementSummary(reactionCount = 1, commentCount = 0, fetchedAtMillis = 1_000)
        ArticleEngagementSummaryStore.put("me", first.address, summary)

        assertEquals(summary, ArticleEngagementSummaryStore.flow("me", first.address).value)
        assertNull(ArticleEngagementSummaryStore.flow("other", first.address).value)
        assertTrue(ArticleEngagementSummaryStore.isFresh("me", first.address, nowMillis = 2_000))
        assertFalse(
            ArticleEngagementSummaryStore.isFresh(
                "me",
                first.address,
                nowMillis = 1_000 + ArticleEngagementSummaryStore.FRESH_DURATION_MILLIS,
            ),
        )

        ArticleEngagementSummaryStore.markStale("me", listOf(first.address))
        assertFalse(ArticleEngagementSummaryStore.isFresh("me", first.address, nowMillis = 2_000))
        assertEquals(1, ArticleEngagementSummaryStore.flow("me", first.address).value?.reactionCount)
    }

    private fun reaction(id: String, address: String, pubkey: String, content: String) =
        event(id, kind = 7, tags = listOf(listOf("a", address)), pubkey = pubkey, content = content)

    private fun comment(id: String, address: String) = event(
        id,
        kind = 1111,
        tags = listOf(listOf("A", address), listOf("K", "30023"), listOf("a", address), listOf("k", "30023")),
    )

    private fun event(
        id: String,
        kind: Int,
        tags: List<List<String>>,
        pubkey: String = "someone",
        content: String = "",
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = 100,
        kind = kind,
        tags = tags,
        content = content,
        sig = "sig",
    )
}
