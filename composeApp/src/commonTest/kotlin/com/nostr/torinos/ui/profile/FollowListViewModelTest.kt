package com.nostr.torinos.ui.profile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class FollowListViewModelTest {
    @Test
    fun waitsUntilEveryTargetRelayCompletes() {
        val targets = setOf("wss://first.example", "wss://second.example")

        assertFalse(
            hasCompletedAllFollowRelays(
                targetRelayUrls = targets,
                completedRelayUrls = setOf("wss://first.example"),
            ),
        )
        assertTrue(
            hasCompletedAllFollowRelays(
                targetRelayUrls = targets,
                completedRelayUrls = targets,
            ),
        )
    }

    @Test
    fun completesImmediatelyWhenThereAreNoTargetRelays() {
        assertTrue(hasCompletedAllFollowRelays(emptySet(), emptySet()))
    }

    @Test
    fun releasesLoadingAfterTheFirstRelayCompletes() {
        assertFalse(hasCompletedAnyFollowRelay(emptySet()))
        assertTrue(hasCompletedAnyFollowRelay(setOf("wss://first.example")))
    }

    @Test
    fun followerRelayWaitCompletesOnRelayEnd() = runTest {
        val completion = CompletableDeferred<Unit>().also { it.complete(Unit) }

        assertTrue(awaitFollowerRelayCompletion(completion, timeoutMillis = 10_000L))
    }

    @Test
    fun followerRelayWaitStopsAtTimeout() = runTest {
        assertFalse(
            awaitFollowerRelayCompletion(
                completion = CompletableDeferred(),
                timeoutMillis = 10_000L,
            ),
        )
    }
}
