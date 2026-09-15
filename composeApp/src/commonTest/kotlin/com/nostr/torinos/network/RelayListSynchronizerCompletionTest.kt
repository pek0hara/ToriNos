package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayListSynchronizerCompletionTest {
    @Test
    fun validEventAllowsEarlyCompletion() {
        assertTrue(
            shouldCompleteRelayListFetch(
                hasReceivedEvent = true,
                completedRelayCount = 0,
                targetRelayCount = 3,
            ),
        )
    }

    @Test
    fun emptyResultRequiresEveryRelayToComplete() {
        assertFalse(
            shouldCompleteRelayListFetch(
                hasReceivedEvent = false,
                completedRelayCount = 1,
                targetRelayCount = 3,
            ),
        )
        assertTrue(
            shouldCompleteRelayListFetch(
                hasReceivedEvent = false,
                completedRelayCount = 3,
                targetRelayCount = 3,
            ),
        )
    }
}
