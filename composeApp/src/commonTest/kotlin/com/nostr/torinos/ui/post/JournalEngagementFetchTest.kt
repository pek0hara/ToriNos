package com.nostr.torinos.ui.post

import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.SubscriptionSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalEngagementFetchTest {
    @Test
    fun partialSnapshotShowsNewValuesWithoutReducingCache() {
        val cached = JournalState(
            reactionCounts = mapOf("note" to 5),
            replyCounts = mapOf("note" to 3),
        )
        val partial = JournalEngagementSnapshot(
            reactionCounts = mapOf("note" to 2),
            replyCounts = mapOf("note" to 4),
            repostCounts = mapOf("note" to 1),
        )

        val displayed = cached.withProgressiveJournalEngagement(partial)

        assertEquals(5, displayed.reactionCounts["note"])
        assertEquals(4, displayed.replyCounts["note"])
        assertEquals(1, displayed.repostCounts["note"])
    }

    @Test
    fun completedSnapshotReplacesCacheWithExactValues() {
        val cached = JournalState(
            reactionCounts = mapOf("note" to 5),
            replyCounts = mapOf("note" to 3),
        )
        val completed = JournalEngagementSnapshot(
            reactionCounts = mapOf("note" to 2),
        )

        val displayed = cached.withCompletedJournalEngagement(setOf("note"), completed)

        assertEquals(2, displayed.reactionCounts["note"])
        assertNull(displayed.replyCounts["note"])
    }

    @Test
    fun completeSnapshotCanReplaceCachedEngagement() {
        val completion = SubscriptionSignal.FetchCompleted(
            outcomes = mapOf(
                "wss://relay-1.example" to RelayOutcome.Eose,
                "wss://relay-2.example" to RelayOutcome.Eose,
            ),
            timedOut = false,
        )

        assertTrue(shouldCommitJournalEngagement(completion))
    }

    @Test
    fun timedOutSnapshotMustNotClearCachedEngagement() {
        val completion = SubscriptionSignal.FetchCompleted(
            outcomes = mapOf(
                "wss://relay-1.example" to RelayOutcome.Eose,
                "wss://relay-2.example" to RelayOutcome.TimedOut,
            ),
            timedOut = true,
        )

        assertFalse(shouldCommitJournalEngagement(completion))
    }

    @Test
    fun unavailableOrMissingRelaySnapshotMustNotClearCachedEngagement() {
        val unavailable = SubscriptionSignal.FetchCompleted(
            outcomes = mapOf(
                "wss://relay-1.example" to RelayOutcome.Eose,
                "wss://relay-2.example" to RelayOutcome.Unavailable("offline"),
            ),
            timedOut = false,
        )
        val noRelay = SubscriptionSignal.FetchCompleted(
            outcomes = emptyMap(),
            timedOut = false,
        )

        assertFalse(shouldCommitJournalEngagement(unavailable))
        assertFalse(shouldCommitJournalEngagement(noRelay))
    }
}
