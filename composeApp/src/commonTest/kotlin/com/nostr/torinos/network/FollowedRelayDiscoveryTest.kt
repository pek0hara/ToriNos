package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowedRelayDiscoveryTest {
    private val expectedRelays = setOf("relay-a", "relay-b")

    @Test
    fun completesWhenEveryBatchHasRespondedFromEveryRelay() {
        assertTrue(
            shouldCompleteFollowedRelayFetch(
                completedRelaysByBatch = listOf(expectedRelays, expectedRelays),
                expectedRelayUrls = expectedRelays,
            ),
        )
    }

    @Test
    fun waitsForEveryRelayInEachBatch() {
        assertFalse(
            shouldCompleteFollowedRelayFetch(
                completedRelaysByBatch = listOf(setOf("relay-a")),
                expectedRelayUrls = expectedRelays,
            ),
        )
    }

    @Test
    fun waitsWhileABatchHasNoResponse() {
        assertFalse(
            shouldCompleteFollowedRelayFetch(
                completedRelaysByBatch = listOf(setOf("relay-a"), emptySet()),
                expectedRelayUrls = expectedRelays,
            ),
        )
    }

    @Test
    fun receivedEventsDoNotHideNewerCopiesFromOtherRelays() {
        assertFalse(
            shouldCompleteFollowedRelayFetch(
                completedRelaysByBatch = listOf(emptySet()),
                expectedRelayUrls = expectedRelays,
            ),
        )
    }
}
