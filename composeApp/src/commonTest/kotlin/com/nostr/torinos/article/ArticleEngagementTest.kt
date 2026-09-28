package com.nostr.torinos.article

import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleEngagementTest {
    private val address = "30023:author:post"
    private val versionIds = setOf("v1", "v2")

    @Test
    fun reactionMatchesByAddressOrByVersionEventTag() {
        assertTrue(event("r1", kind = 7, tags = listOf(listOf("a", address))).isReactionToArticle(address, versionIds))
        assertTrue(event("r2", kind = 7, tags = listOf(listOf("e", "v1"))).isReactionToArticle(address, versionIds))
        assertFalse(event("r3", kind = 7, tags = listOf(listOf("e", "other"))).isReactionToArticle(address, versionIds))
        assertFalse(event("r4", kind = 1, tags = listOf(listOf("a", address))).isReactionToArticle(address, versionIds))
    }

    @Test
    fun summarizeCountsLikesEmojisAndOwnReactionsOnce() {
        val events = listOf(
            reaction("like-1", pubkey = "alice", content = "+"),
            reaction("like-2", pubkey = "me", content = ""),
            reaction("like-2", pubkey = "me", content = ""),
            reaction("dislike", pubkey = "bob", content = "-"),
            reaction("uni-1", pubkey = "carol", content = "🔥"),
            reaction("uni-2", pubkey = "dave", content = "🔥"),
            reaction(
                "custom",
                pubkey = "me",
                content = ":party:",
                extraTags = listOf(listOf("emoji", "party", "https://example.com/party.png")),
            ),
            reaction("other-target", pubkey = "erin", content = "+", target = "unrelated"),
        )

        val summary = summarizeArticleReactions(events, address, versionIds, ownPubkey = "me")

        assertEquals(2, summary.likeCount)
        assertEquals(listOf(UnicodeReaction("🔥", count = 2)), summary.unicodeReactions)
        assertEquals(listOf(CustomReaction("party", "https://example.com/party.png")), summary.customReactions)
        assertEquals("like-2", summary.ownLikeEventId)
        assertEquals(
            mapOf(ReactionOption.Custom("party", "https://example.com/party.png").key to "custom"),
            summary.ownEmojiReactionEventIds,
        )
        assertEquals(5, summary.totalCount)
    }

    @Test
    fun summarizeSkipsMutedAuthors() {
        val summary = summarizeArticleReactions(
            listOf(reaction("r", pubkey = "muted", content = "+")),
            address,
            versionIds,
            ownPubkey = null,
            isMuted = { it == "muted" },
        )

        assertEquals(0, summary.totalCount)
    }

    @Test
    fun nip22CommentIsTopLevelOnlyWhenParentIsTheArticle() {
        val topLevel = event(
            "c1",
            kind = 1111,
            tags = listOf(listOf("A", address), listOf("K", "30023"), listOf("a", address), listOf("k", "30023")),
        )
        val nested = event(
            "c2",
            kind = 1111,
            tags = listOf(listOf("A", address), listOf("K", "30023"), listOf("e", "c1"), listOf("k", "1111")),
        )

        assertTrue(topLevel.isTopLevelArticleComment(address, versionIds))
        assertFalse(nested.isTopLevelArticleComment(address, versionIds))
    }

    @Test
    fun legacyTextNoteReplyIsCommentUnlessMentionOrReplyToAnotherNote() {
        val plain = event("n1", kind = 1, tags = listOf(listOf("a", address)))
        val withRoot = event(
            "n2",
            kind = 1,
            tags = listOf(listOf("a", address, "", "root"), listOf("e", "v2", "", "root")),
        )
        val mention = event("n3", kind = 1, tags = listOf(listOf("a", address, "", "mention")))
        val replyToNote = event(
            "n4",
            kind = 1,
            tags = listOf(listOf("a", address, "", "root"), listOf("e", "someone-else", "", "reply")),
        )

        assertTrue(plain.isTopLevelArticleComment(address, versionIds))
        assertTrue(withRoot.isTopLevelArticleComment(address, versionIds))
        assertFalse(mention.isTopLevelArticleComment(address, versionIds))
        assertFalse(replyToNote.isTopLevelArticleComment(address, versionIds))
    }

    @Test
    fun topLevelCommentsAreDedupedSortedOldestFirstAndSkipMuted() {
        val newer = event("b", kind = 1, tags = listOf(listOf("a", address)), createdAt = 200)
        val older = event("a", kind = 1, tags = listOf(listOf("a", address)), createdAt = 100)
        val muted = event("m", kind = 1, tags = listOf(listOf("a", address)), createdAt = 150, pubkey = "muted")

        val comments = articleTopLevelComments(
            listOf(newer, older, newer, muted),
            address,
            versionIds,
            isMuted = { it == "muted" },
        )

        assertEquals(listOf("a", "b"), comments.map { it.id })
    }

    @Test
    fun engagementFiltersQueryByAddressAndVersionIds() {
        val filters = articleEngagementFilters(address, listOf("v1", "v1", "v2"), limit = 100)
        assertEquals(4, filters.size)
        assertEquals(listOf(5), articleEngagementFilters(address, listOf("v1"), 100, ownPubkey = "me").last().kinds)

        assertEquals(listOf(address), filters[0].aTags)
        assertEquals(listOf("v1", "v2"), filters[1].eTags)
        assertEquals(listOf(address), filters[2].rootAddressTags)
        assertEquals(listOf(1), filters[3].kinds)
    }

    @Test
    fun commentReplyTargetProducesTopLevelNip22ArticleComment() {
        val article = com.nostr.torinos.model.ArticleItem(
            event = event("v2", kind = 30023, tags = listOf(listOf("d", "post")), pubkey = "author"),
            meta = com.nostr.torinos.model.ArticleMeta(
                identifier = "post",
                title = null,
                summary = null,
                imageUrl = null,
                publishedAt = null,
                topics = emptyList(),
            ),
        )
        val target = article.commentReplyTarget()
        val comment = event("c", kind = target.eventKind, tags = target.tags())

        assertEquals(1111, target.eventKind)
        assertTrue(comment.isTopLevelArticleComment(address, versionIds))
    }

    @Test
    fun deletedOwnReactionIsExcludedOnlyWhenDeletedByItsAuthor() {
        val mine = reaction("mine", pubkey = "me", content = "+")
        val others = reaction("others", pubkey = "alice", content = "+")
        val deletions = listOf(
            event("d1", kind = 5, pubkey = "me", tags = listOf(listOf("e", "mine"))),
            event("d2", kind = 5, pubkey = "me", tags = listOf(listOf("e", "others"))),
        )

        assertEquals(listOf("others"), withoutDeletedEvents(listOf(mine, others), deletions).map { it.id })
        val filter = ownRecentDeletionsFilter("me")
        assertEquals(listOf(5), filter.kinds)
        assertEquals(listOf("me"), filter.authors)
    }

    private fun reaction(
        id: String,
        pubkey: String,
        content: String,
        target: String? = null,
        extraTags: List<List<String>> = emptyList(),
    ) = event(
        id,
        kind = 7,
        pubkey = pubkey,
        content = content,
        tags = (if (target == null) listOf(listOf("a", address)) else listOf(listOf("e", target))) + extraTags,
    )

    private fun event(
        id: String,
        kind: Int,
        tags: List<List<String>>,
        createdAt: Long = 100,
        pubkey: String = "someone",
        content: String = "",
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        sig = "sig",
    )
}
