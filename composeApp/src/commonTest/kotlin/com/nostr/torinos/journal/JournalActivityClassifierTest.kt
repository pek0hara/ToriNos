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
    /** [OWNER]だけのジャーナルで、[OWNER]に当たる種類。 */
    private fun classify(event: com.nostr.torinos.model.NostrEvent): Set<JournalActivityKind> =
        JournalActivityClassifier.classify(event, SelfOwner, byPTag)[OWNER].orEmpty()

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
    fun ownLikeIsLike() {
        val like = reaction("l", targetId = "t", pubkey = OWNER, targetAuthor = OTHER)
        assertEquals(setOf(Like), classify(like))
    }

    @Test
    fun likeBetweenMembersIsLikeForTheSenderAndReceivedLikeForTheTarget() {
        val like = reaction("l", targetId = "t", pubkey = OWNER, targetAuthor = OTHER)
        assertEquals(
            mapOf(OWNER to setOf(Like), OTHER to setOf(ReceivedLike)),
            JournalActivityClassifier.classify(like, JournalMembers(OWNER, OTHER), byPTag),
        )
    }

    @Test
    fun eachMembersOwnNotesAreClassifiedForThatMember() {
        val members = JournalMembers(OWNER, OTHER)
        assertEquals(mapOf(OTHER to setOf(Post)), JournalActivityClassifier.classify(post("a", pubkey = OTHER), members, byPTag))
        assertEquals(emptyMap(), JournalActivityClassifier.classify(post("b", pubkey = "stranger"), members, byPTag))
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
        assertEquals(setOf(Like, ReceivedLike), classify(selfLike))
    }

    @Test
    fun articlesAreNoLongerJournalActivities() {
        assertEquals(emptySet(), classify(event("a", kind = NIP23_ARTICLE_KIND)))
    }

    @Test
    fun noSelectionShowsPostsRepostsAndReplies() {
        assertEquals(setOf(Post, Repost, Reply), effectiveJournalKinds(emptySet()))
    }

    @Test
    fun explicitSelectionShowsOnlySelectedKinds() {
        assertEquals(setOf(Repost, Reply), effectiveJournalKinds(setOf(Repost, Reply)))
    }

    @Test
    fun everyKindIsAvailable() {
        assertEquals(JournalActivityKind.entries.toSet(), availableJournalKinds())
        assertEquals(setOf(Like), effectiveJournalKinds(setOf(Like)))
    }

    @Test
    fun receivedLikeHelperUsesThePTagWhenTheTargetAuthorIsUnknown() {
        assertTrue(reaction("x1", targetId = "unknown-target").isReceivedLikeForJournal(OWNER))
        assertFalse(reaction("x2", targetId = "unknown-target", content = "-").isReceivedLikeForJournal(OWNER))
    }
}
