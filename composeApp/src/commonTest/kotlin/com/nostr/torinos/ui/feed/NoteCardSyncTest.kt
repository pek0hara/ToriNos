package com.nostr.torinos.ui.feed

import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.ui.timeline.NoteCardSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class NoteCardSyncTest {
    @Test
    fun threadSnapshotUpdatesFeedCardEngagement() {
        val initial = FeedViewModel.UiState(
            replyCounts = mapOf("note" to 1),
            reactionCounts = mapOf("note" to 2),
            likeReactionCounts = mapOf("note" to 1),
        )

        val updated = initial.withCardSnapshot(
            NoteCardSnapshot(
                sessionId = "session",
                eventId = "note",
                replyCount = 3,
                engagement = NoteEngagementState(
                    reactionCount = 4,
                    likeReactionCount = 3,
                    ownLikeEventId = "my-like",
                ),
            ),
        )

        assertEquals(3, updated.replyCounts["note"])
        assertEquals(4, updated.reactionCounts["note"])
        assertEquals(3, updated.likeReactionCounts["note"])
        assertEquals("my-like", updated.likedReactions["note"])
    }

    @Test
    fun olderReplyCountDoesNotDecreaseFeedCard() {
        val initial = FeedViewModel.UiState(replyCounts = mapOf("note" to 5))

        val updated = initial.withCardSnapshot(
            NoteCardSnapshot(null, "note", replyCount = 3, engagement = null),
        )

        assertEquals(5, updated.replyCounts["note"])
    }
}
