package com.nostr.torinos.journal

import com.nostr.torinos.journal.JournalActivityKind.Like
import com.nostr.torinos.journal.JournalActivityKind.Post
import com.nostr.torinos.journal.JournalActivityKind.ReceivedLike
import com.nostr.torinos.journal.JournalActivityKind.Reply
import com.nostr.torinos.journal.JournalActivityKind.Repost
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalActivityClassifierTest {
    private fun classify(event: com.nostr.torinos.model.NostrEvent, owner: JournalOwner = SelfOwner) =
        JournalActivityClassifier.classify(event, owner, byPTag)

    @Test
    fun ownKindOneIsPostOrReplyByReplyTarget() {
        assertEquals(setOf(Post), classify(post("a")))
        assertEquals(setOf(Reply), classify(reply("b", parentId = "a")))
    }

    @Test
    fun othersNotesAndRepostsAreNotJournalActivities() {
        assertEquals(emptySet(), classify(post("a", pubkey = OTHER)))
        assertEquals(emptySet(), classify(event("r", kind = 6, pubkey = OTHER)))
    }

    @Test
    fun onlySupportedCommentsCountAsReplies() {
        assertEquals(setOf(Reply), classify(supportedComment("c", rootId = "root")))
        assertEquals(emptySet(), classify(event("c2", kind = COMMENT_EVENT_KIND, tags = listOf(listOf("e", "x")))))
    }

    @Test
    fun ownRepostIsRepost() {
        assertEquals(setOf(Repost), classify(event("r", kind = 6)))
    }

    @Test
    fun ownLikeIsLikeOnlyInOwnJournal() {
        val like = reaction("l", targetId = "t", pubkey = OWNER, targetAuthor = OTHER)
        assertEquals(setOf(Like), classify(like, SelfOwner))
        assertEquals(emptySet(), classify(like, UserOwner))
    }

    @Test
    fun receivedReactionsExceptDislikeAreReceivedLikes() {
        assertEquals(setOf(ReceivedLike), classify(reaction("l1", targetId = "t")))
        assertEquals(setOf(ReceivedLike), classify(reaction("l2", targetId = "t", content = "🔥")))
        assertEquals(emptySet(), classify(reaction("l3", targetId = "t", content = "-")))
        assertEquals(emptySet(), classify(reaction("l4", targetId = "t", targetAuthor = "someone")))
    }

    @Test
    fun likingYourOwnPostIsBothLikeAndReceivedLike() {
        val selfLike = reaction("l", targetId = "t", pubkey = OWNER, targetAuthor = OWNER)
        assertEquals(setOf(Like, ReceivedLike), classify(selfLike, SelfOwner))
        assertEquals(setOf(ReceivedLike), classify(selfLike, UserOwner))
    }

    @Test
    fun articlesAreNoLongerJournalActivities() {
        assertEquals(emptySet(), classify(event("a", kind = NIP23_ARTICLE_KIND)))
    }

    @Test
    fun noSelectionShowsPostsRepostsAndReplies() {
        assertEquals(setOf(Post, Repost, Reply), effectiveJournalKinds(emptySet(), isSelf = true))
    }

    @Test
    fun explicitSelectionShowsOnlySelectedKinds() {
        assertEquals(setOf(Repost, Reply), effectiveJournalKinds(setOf(Repost, Reply), isSelf = true))
    }

    @Test
    fun likeIsNotAvailableInOthersJournal() {
        assertFalse(Like in availableJournalKinds(isSelf = false))
        assertTrue(Like in availableJournalKinds(isSelf = true))
        assertEquals(setOf(Post, Repost, Reply), effectiveJournalKinds(setOf(Like), isSelf = false))
        assertEquals(setOf(ReceivedLike), effectiveJournalKinds(setOf(Like, ReceivedLike), isSelf = false))
    }

    @Test
    fun receivedLikeHelperUsesThePTagWhenTheTargetAuthorIsUnknown() {
        assertTrue(reaction("x1", targetId = "unknown-target").isReceivedLikeForJournal(OWNER))
        assertFalse(reaction("x2", targetId = "unknown-target", content = "-").isReceivedLikeForJournal(OWNER))
    }
}
