package com.nostr.torinos.ui.relay

import com.nostr.torinos.network.RelayEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelaySettingsViewModelTest {
    @Test
    fun relayListCannotBePublishedBeforeInitialFetchCompletes() {
        val pending = PublishedRelayListUiState(
            pendingAdditions = setOf("wss://new.example"),
            hasCompletedInitialFetch = false,
        )
        val fetched = pending.copy(hasCompletedInitialFetch = true)

        assertFalse(pending.canPublishChanges)
        assertTrue(fetched.canPublishChanges)
    }

    @Test
    fun publishedRelayDiffContainsOnlyExplicitlyChangedRelays() {
        val result = calculatePublishedRelayListChanges(
            changedUrls = setOf("wss://add.example", "wss://remove.example"),
            committedEntries = listOf(
                RelayEntry("wss://existing.example", enabled = true),
                RelayEntry("wss://remove.example", enabled = true),
            ),
            draftEntries = listOf(
                RelayEntry("wss://existing.example", enabled = true),
                RelayEntry("wss://add.example", enabled = true),
                RelayEntry("wss://remove.example", enabled = false),
            ),
        )

        assertEquals(
            PublishedRelayListChanges(
                additions = setOf("wss://add.example"),
                removals = setOf("wss://remove.example"),
            ),
            result,
        )
    }

    @Test
    fun restoredRelayStateHasNoDiff() {
        val entries = listOf(RelayEntry("wss://existing.example", enabled = true))

        val result = calculatePublishedRelayListChanges(
            changedUrls = setOf("wss://existing.example"),
            committedEntries = entries,
            draftEntries = entries,
        )

        assertEquals(PublishedRelayListChanges(emptySet(), emptySet()), result)
    }
}
