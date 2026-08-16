package com.nostr.torinos.ui.profile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
