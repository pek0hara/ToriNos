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

    @Test
    fun stagedRelaysAreHiddenFromRelayList() {
        val entries = listOf(
            RelayEntry("wss://existing.example", enabled = true),
            RelayEntry("wss://add.example", enabled = true),
            RelayEntry("wss://remove.example", enabled = false),
        )

        val result = visibleRelayEntries(
            entries = entries,
            stagedRelayUrls = setOf("wss://add.example", "wss://remove.example"),
            searchQuery = "",
        )

        assertEquals(listOf(RelayEntry("wss://existing.example", enabled = true)), result)
    }

    @Test
    fun stagedChangesAreRebasedOntoExternalRelayUpdate() {
        val result = rebaseRelayEntryChanges(
            changedUrls = setOf("wss://add.example", "wss://remove.example"),
            newCommittedEntries = listOf(
                RelayEntry("wss://existing.example", enabled = true),
                RelayEntry("wss://remove.example", enabled = true),
                RelayEntry("wss://external.example", enabled = true),
            ),
            currentDraftEntries = listOf(
                RelayEntry("wss://existing.example", enabled = true),
                RelayEntry("wss://add.example", enabled = true),
            ),
        )

        assertEquals(
            listOf(
                RelayEntry("wss://existing.example", enabled = true),
                RelayEntry("wss://external.example", enabled = true),
                RelayEntry("wss://add.example", enabled = true),
            ),
            result,
        )
    }

    @Test
    fun relayPresentationUsesNip11MetadataWithHostFallbacks() {
        assertEquals(
            "damus.io",
            relayDisplayName("wss://relay.damus.io", " damus.io "),
        )
        assertEquals(
            "relay.damus.io",
            relayDisplayName("wss://relay.damus.io", null),
        )
        assertEquals(
            "https://cdn.example.com/relay.png",
            relayIconUrl("wss://relay.damus.io", " https://cdn.example.com/relay.png "),
        )
        assertEquals(
            "https://relay.damus.io/favicon.ico",
            relayIconUrl("wss://relay.damus.io", null),
        )
    }
}
