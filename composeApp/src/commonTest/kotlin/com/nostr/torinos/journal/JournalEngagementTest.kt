package com.nostr.torinos.journal

import com.nostr.torinos.engagement.EngagementAction
import com.nostr.torinos.engagement.EngagementOperationId
import com.nostr.torinos.engagement.EngagementReducer
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.SubscriptionSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalEngagementTest {
    private val note = "note"

    @Test
    fun aggregatorCountsReactionsRepliesRepostsAndQuotes() {
        val aggregator = JournalEngagementAggregator(setOf(note), ownPubkey = OWNER)

        aggregator.add(reaction("like", targetId = note, pubkey = OTHER))
        aggregator.add(reaction("own", targetId = note, pubkey = OWNER))
        aggregator.add(reaction("fire", targetId = note, pubkey = "third", content = "🔥"))
        aggregator.add(reply("reply", parentId = note, pubkey = OTHER))
        aggregator.add(event("repost", kind = 6, pubkey = OTHER, tags = listOf(listOf("e", note))))
        aggregator.add(event("quote", kind = 1, pubkey = "fourth", tags = listOf(listOf("q", note))))

        val engagement = aggregator.snapshot().getValue(note)
        assertEquals(3, engagement.summary.reactionCount)
        assertEquals(2, engagement.summary.likeReactionCount)
        assertEquals("own", engagement.summary.ownLikeEventId)
        assertEquals(1, engagement.summary.unicodeReactions.single().count)
        assertEquals(1, engagement.replyCount)
        assertEquals(listOf("reply"), engagement.replies.map { it.id })
        assertEquals(2, engagement.summary.repostCount)
        assertEquals(listOf(OTHER, "fourth"), engagement.repostPubkeys)
        assertEquals(3, engagement.reactionEvents.size)
    }

    @Test
    fun kindOneRepliesUseTheReplyMarkerLikeTheFeed() {
        val aggregator = JournalEngagementAggregator(setOf(note, "root"), ownPubkey = null)
        val markedReply = event(
            "r",
            kind = 1,
            pubkey = OTHER,
            tags = listOf(listOf("e", note, "", "reply"), listOf("e", "root", "", "root")),
        )

        assertEquals(setOf(note), aggregator.add(markedReply))
        assertEquals(1, aggregator.snapshot().getValue(note).replyCount)
        assertNull(aggregator.snapshot()["root"])
    }

    @Test
    fun eventsForOtherNotesAreIgnored() {
        val aggregator = JournalEngagementAggregator(setOf(note), ownPubkey = null)
        assertEquals(emptySet(), aggregator.add(reaction("x", targetId = "elsewhere")))
        assertTrue(aggregator.snapshot().isEmpty())
    }

    @Test
    fun partialResultsNeverReduceCachedValues() {
        val cached = mapOf(
            note to JournalNoteEngagement(summary = NoteEngagementState(reactionCount = 5), replyCount = 3),
        )
        val partial = mapOf(
            note to JournalNoteEngagement(
                summary = NoteEngagementState(reactionCount = 2, repostCount = 1),
                replyCount = 4,
            ),
        )

        val merged = cached.mergeProgressive(partial).getValue(note)

        assertEquals(5, merged.summary.reactionCount)
        assertEquals(4, merged.replyCount)
        assertEquals(1, merged.summary.repostCount)
    }

    @Test
    fun completedResultsReplaceCachedValuesExactly() {
        val cached = mapOf(
            note to JournalNoteEngagement(summary = NoteEngagementState(reactionCount = 5), replyCount = 3),
            "other" to JournalNoteEngagement(replyCount = 9),
        )

        val replaced = cached.replaceCompleted(
            setOf(note),
            mapOf(note to JournalNoteEngagement(summary = NoteEngagementState(reactionCount = 2))),
        )

        assertEquals(2, replaced.getValue(note).summary.reactionCount)
        assertEquals(0, replaced.getValue(note).replyCount)
        assertEquals(9, replaced.getValue("other").replyCount)
        assertEquals(0, cached.replaceCompleted(setOf(note), emptyMap()).getValue(note).summary.reactionCount)
    }

    @Test
    fun completedResultsKeepAnInFlightLikeAndItsCount() {
        // 送信中のいいねがある間に完了結果が届いても +1 を失わず、送信失敗時に実際の件数へ戻る。
        val operation = EngagementOperationId("op")
        val optimistic = EngagementReducer.reduce(
            NoteEngagementState(reactionCount = 4, likeReactionCount = 4),
            EngagementAction.Begin(operation, EngagementRequest.AddLike),
        )
        val cached = mapOf(note to JournalNoteEngagement(summary = optimistic))
        val fetched = NoteEngagementState(reactionCount = 4, likeReactionCount = 4)

        val replaced = cached.replaceCompleted(setOf(note), mapOf(note to JournalNoteEngagement(summary = fetched)))
        val summary = replaced.getValue(note).summary
        assertEquals(5, summary.reactionCount)
        assertTrue(summary.pendingOperations.isNotEmpty())

        val rolledBack = EngagementReducer.reduce(summary, EngagementAction.Rollback(operation))
        assertEquals(4, rolledBack.reactionCount)
        assertEquals(4, rolledBack.likeReactionCount)
    }

    @Test
    fun completedResultsAlreadyContainingThePublishedLikeAreNotCountedTwice() {
        val operation = EngagementOperationId("op")
        val optimistic = EngagementReducer.reduce(
            NoteEngagementState(reactionCount = 4),
            EngagementAction.Begin(operation, EngagementRequest.AddLike),
        )
        val fetched = NoteEngagementState(reactionCount = 5, likeReactionCount = 1, ownLikeEventId = "published")

        val summary = mapOf(note to JournalNoteEngagement(summary = optimistic))
            .replaceCompleted(setOf(note), mapOf(note to JournalNoteEngagement(summary = fetched)))
            .getValue(note).summary

        assertEquals(5, summary.reactionCount)
        val committed = EngagementReducer.reduce(summary, EngagementAction.Commit(operation, "published"))
        assertEquals(5, committed.reactionCount)
        assertEquals("published", committed.ownLikeEventId)
        assertEquals(5, EngagementReducer.reduce(summary, EngagementAction.Rollback(operation)).reactionCount)
    }

    @Test
    fun rebaseKeepsAPendingEmojiRemovalHidden() {
        val option = ReactionOption.Unicode("🔥")
        val operation = EngagementOperationId("op")
        val before = NoteEngagementState(
            reactionCount = 2,
            unicodeReactions = listOf(com.nostr.torinos.model.UnicodeReaction("🔥", 2)),
            ownEmojiReactionEventIds = mapOf(option.key to "mine"),
        )
        val pending = EngagementReducer.reduce(before, EngagementAction.Begin(operation, EngagementRequest.RemoveEmoji(option)))

        val rebased = EngagementReducer.rebase(before, pending.pendingOperations)

        assertEquals(1, rebased.reactionCount)
        assertEquals(1, rebased.unicodeReactions.single().count)
        assertFalse(option.key in rebased.ownEmojiReactionEventIds)
        val rolledBack = EngagementReducer.reduce(rebased, EngagementAction.Rollback(operation))
        assertEquals(2, rolledBack.reactionCount)
        assertEquals("mine", rolledBack.ownEmojiReactionEventIds[option.key])
    }

    @Test
    fun fetchIsCommittedOnlyWhenEveryRelayFinished() {
        fun completion(vararg outcomes: RelayOutcome, timedOut: Boolean = false) = SubscriptionSignal.FetchCompleted(
            outcomes = outcomes.withIndex().associate { (i, outcome) -> "wss://relay-$i" to outcome },
            timedOut = timedOut,
        )

        assertTrue(shouldCommitJournalFetch(completion(RelayOutcome.Eose, RelayOutcome.Eose)))
        assertFalse(shouldCommitJournalFetch(completion(RelayOutcome.Eose, RelayOutcome.TimedOut, timedOut = true)))
        assertFalse(shouldCommitJournalFetch(completion(RelayOutcome.Eose, RelayOutcome.Unavailable("offline"))))
        assertFalse(shouldCommitJournalFetch(completion()))
    }
}
