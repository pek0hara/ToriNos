package com.nostr.torinos.engagement

import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.ReactionOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EngagementReducerTest {
    @Test
    fun addLikeCommitsSignedEventIdWithoutDoubleCounting() {
        val operationId = EngagementOperationId("like")
        val optimistic = EngagementReducer.reduce(
            NoteEngagementState(),
            EngagementAction.Begin(operationId, EngagementRequest.AddLike),
        )

        assertEquals(1, optimistic.reactionCount)
        assertEquals(1, optimistic.likeReactionCount)
        assertTrue(optimistic.hasOwnReaction)
        assertTrue(optimistic.isReactionPending)
        assertNull(optimistic.ownLikeEventId)

        val committed = EngagementReducer.reduce(
            optimistic,
            EngagementAction.Commit(operationId, "reaction-id"),
        )

        assertEquals(1, committed.reactionCount)
        assertEquals(1, committed.likeReactionCount)
        assertEquals("reaction-id", committed.ownLikeEventId)
        assertFalse(committed.isReactionPending)
    }

    @Test
    fun addEmojiRollbackOnlyRevertsItsOwnDelta() {
        val option = ReactionOption.Custom("bird", "https://example.com/bird.png")
        val operationId = EngagementOperationId("emoji")
        val optimistic = EngagementReducer.reduce(
            NoteEngagementState(reactionCount = 4),
            EngagementAction.Begin(operationId, EngagementRequest.AddEmoji(option)),
        )
        val afterRemoteReaction = optimistic.copy(reactionCount = optimistic.reactionCount + 1)

        val rolledBack = EngagementReducer.reduce(
            afterRemoteReaction,
            EngagementAction.Rollback(operationId),
        )

        assertEquals(5, rolledBack.reactionCount)
        assertEquals(emptyList(), rolledBack.customReactions)
        assertFalse(rolledBack.hasOwnReaction)
    }

    @Test
    fun removeLikeRollbackRestoresIdAndCounts() {
        val operationId = EngagementOperationId("unlike")
        val initial = NoteEngagementState(
            reactionCount = 3,
            likeReactionCount = 2,
            ownLikeEventId = "old-reaction-id",
        )
        val optimistic = EngagementReducer.reduce(
            initial,
            EngagementAction.Begin(operationId, EngagementRequest.RemoveLike),
        )

        assertEquals(2, optimistic.reactionCount)
        assertEquals(1, optimistic.likeReactionCount)
        assertNull(optimistic.ownLikeEventId)
        assertFalse(optimistic.hasOwnReaction)

        val rolledBack = EngagementReducer.reduce(
            optimistic,
            EngagementAction.Rollback(operationId),
        )

        assertEquals(initial, rolledBack)
    }

    @Test
    fun removeEmojiAtZeroDoesNotAddCountOnRollback() {
        val option = ReactionOption.Custom("bird", "url")
        val operationId = EngagementOperationId("remove-emoji")
        val initial = NoteEngagementState(
            ownEmojiReactionEventIds = mapOf(option.key to "reaction-id"),
        )
        val optimistic = EngagementReducer.reduce(
            initial,
            EngagementAction.Begin(operationId, EngagementRequest.RemoveEmoji(option)),
        )
        val rolledBack = EngagementReducer.reduce(
            optimistic,
            EngagementAction.Rollback(operationId),
        )

        assertEquals(0, rolledBack.reactionCount)
        assertEquals(emptyList(), rolledBack.customReactions)
        assertEquals(initial.ownEmojiReactionEventIds, rolledBack.ownEmojiReactionEventIds)
    }

    @Test
    fun reactionSlotRejectsConcurrentOperationButRepostCanRun() {
        val likeId = EngagementOperationId("like")
        val liking = EngagementReducer.reduce(
            NoteEngagementState(),
            EngagementAction.Begin(likeId, EngagementRequest.AddLike),
        )

        val duplicate = EngagementReducer.reduce(
            liking,
            EngagementAction.Begin(EngagementOperationId("emoji"), EngagementRequest.AddEmoji(ReactionOption.Unicode("👍"))),
        )
        assertSame(liking, duplicate)

        val withRepost = EngagementReducer.reduce(
            liking,
            EngagementAction.Begin(EngagementOperationId("repost"), EngagementRequest.AddRepost),
        )
        assertEquals(1, withRepost.repostCount)
        assertTrue(withRepost.isRepostPending)
    }

    @Test
    fun staleCompletionIsIgnored() {
        val state = NoteEngagementState(
            customReactions = listOf(CustomReaction("bird", "url")),
        )

        assertSame(
            state,
            EngagementReducer.reduce(
                state,
                EngagementAction.Commit(EngagementOperationId("stale"), "event"),
            ),
        )
        assertSame(
            state,
            EngagementReducer.reduce(
                state,
                EngagementAction.Rollback(EngagementOperationId("stale")),
            ),
        )
    }

    @Test
    fun removeRepostFailureRestoresEventIdAndCount() {
        val operationId = EngagementOperationId("unrepost")
        val initial = NoteEngagementState(
            repostCount = 4,
            ownRepostEventId = "repost-id",
        )
        val optimistic = EngagementReducer.reduce(
            initial,
            EngagementAction.Begin(operationId, EngagementRequest.RemoveRepost),
        )

        assertEquals(3, optimistic.repostCount)
        assertNull(optimistic.ownRepostEventId)

        assertEquals(
            initial,
            EngagementReducer.reduce(optimistic, EngagementAction.Rollback(operationId)),
        )
    }
}
