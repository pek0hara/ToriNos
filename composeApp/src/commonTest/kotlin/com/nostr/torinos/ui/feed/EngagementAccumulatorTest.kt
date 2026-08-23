package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EngagementAccumulatorTest {
    @Test
    fun ownLikeUpdatesCountsAndEventIdWithoutMutatingInput() {
        val initial = FeedViewModel.UiState(
            reactionCounts = mapOf(TARGET_ID to 2),
            likeReactionCounts = mapOf(TARGET_ID to 1),
        )

        val updated = EngagementAccumulator.reaction(
            state = initial,
            targetId = TARGET_ID,
            event = event(id = "like-id", pubkey = OWN_PUBKEY, kind = 7, content = "+"),
            isOwn = true,
        )

        assertEquals(3, updated.reactionCounts[TARGET_ID])
        assertEquals(2, updated.likeReactionCounts[TARGET_ID])
        assertEquals("like-id", updated.likedReactions[TARGET_ID])
        assertEquals(2, initial.reactionCounts[TARGET_ID])
        assertFalse(TARGET_ID in initial.likedReactions)
    }

    @Test
    fun customReactionAggregatesByEmojiAndTracksOwnEvent() {
        val imageUrl = "https://example.com/bird.webp"
        val option = ReactionOption.Custom("bird", imageUrl)
        val initial = FeedViewModel.UiState(
            customReactions = mapOf(TARGET_ID to listOf(CustomReaction("bird", imageUrl))),
        )
        val reaction = event(
            id = "emoji-id",
            pubkey = OWN_PUBKEY,
            kind = 7,
            content = ":bird:",
            tags = listOf(listOf("e", TARGET_ID), listOf("emoji", "bird", imageUrl)),
        )

        val updated = EngagementAccumulator.reaction(initial, TARGET_ID, reaction, isOwn = true)

        assertEquals(listOf(CustomReaction("bird", imageUrl, count = 2)), updated.customReactions[TARGET_ID])
        assertEquals("emoji-id", updated.ownEmojiReactionEventIds[TARGET_ID]?.get(option.key))
    }

    @Test
    fun repliesAreKeptInCreationOrder() {
        val newer = event(id = "newer", createdAt = 20, kind = 1)
        val older = event(id = "older", createdAt = 10, kind = 1)
        val initial = FeedViewModel.UiState(
            replyCounts = mapOf(TARGET_ID to 1),
            replies = mapOf(TARGET_ID to listOf(newer)),
        )

        val updated = EngagementAccumulator.reply(initial, TARGET_ID, older)

        assertEquals(2, updated.replyCounts[TARGET_ID])
        assertEquals(listOf("older", "newer"), updated.replies[TARGET_ID]?.map { it.id })
    }

    @Test
    fun quoteRepostIncrementsEveryTarget() {
        val initial = FeedViewModel.UiState(repostCounts = mapOf("a" to 2))

        val updated = EngagementAccumulator.quoteReposts(initial, listOf("a", "b"))

        assertEquals(mapOf("a" to 3, "b" to 1), updated.repostCounts)
        assertEquals(mapOf("a" to 2), initial.repostCounts)
    }

    @Test
    fun reconciliationUsesOnlyEventsPublishedByCurrentAccount() {
        val ownEmoji = event(
            id = "own-emoji",
            pubkey = OWN_PUBKEY,
            kind = 7,
            content = "🎉",
            tags = listOf(listOf("e", TARGET_ID)),
        )
        val otherLike = event(
            id = "other-like",
            pubkey = "other",
            kind = 7,
            content = "+",
            tags = listOf(listOf("e", TARGET_ID)),
        )
        val ownRepost = event(
            id = "own-repost",
            pubkey = OWN_PUBKEY,
            kind = 6,
            tags = listOf(listOf("e", TARGET_ID)),
        )

        val updated = EngagementAccumulator.reconcileOwnEngagement(
            state = FeedViewModel.UiState(),
            ownPubkey = OWN_PUBKEY,
            reactionEvents = listOf(ownEmoji, otherLike),
            repostEvents = listOf(ownRepost),
        )

        assertFalse(TARGET_ID in updated.likedReactions)
        assertEquals("own-emoji", updated.ownEmojiReactionEventIds[TARGET_ID]?.values?.single())
        assertEquals("own-repost", updated.repostedEvents[TARGET_ID])
    }

    private fun event(
        id: String,
        pubkey: String = "author",
        createdAt: Long = 1,
        kind: Int,
        content: String = "",
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        sig = "signature",
    )

    private companion object {
        const val TARGET_ID = "target"
        const val OWN_PUBKEY = "me"
    }
}
