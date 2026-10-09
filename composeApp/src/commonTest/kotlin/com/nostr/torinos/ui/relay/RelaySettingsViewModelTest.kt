package com.nostr.torinos.ui.relay

import com.nostr.torinos.network.RelayEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelaySettingsViewModelTest {
    @Test
    fun anonymousRelaySettingsCanBeSavedWithoutWaitingForPublishedList() {
        val state = initialPublishedRelayListUiState(canSyncPublishedRelayList = false).copy(
            pendingAdditions = setOf("wss://local.example"),
        )

        assertFalse(state.isLoading)
        assertFalse(state.isAwaitingFirstResponse)
        assertTrue(state.canPublishChanges)
    }

    @Test
    fun accountRelaySettingsWaitForPublishedListBeforeSaving() {
        val state = initialPublishedRelayListUiState(canSyncPublishedRelayList = true).copy(
            pendingAdditions = setOf("wss://account.example"),
        )

        assertTrue(state.isLoading)
        assertTrue(state.isAwaitingFirstResponse)
        assertFalse(state.canPublishChanges)
    }

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
    fun pendingRemovalsStayInActiveSectionAndCandidatesSortByUsage() {
        val entries = listOf(
            RelayEntry("wss://existing.example", enabled = true),
            RelayEntry("wss://remove.example", enabled = false),
            RelayEntry("wss://rare.example", enabled = false),
            RelayEntry("wss://popular.example", enabled = false),
        )

        val result = relaySections(
            entries = entries,
            pendingRemovals = setOf("wss://remove.example"),
            usageCounts = mapOf("wss://popular.example" to 5, "wss://rare.example" to 1),
            searchQuery = "",
        )

        assertEquals(
            listOf("wss://existing.example", "wss://remove.example"),
            result.active.map { it.url },
        )
        assertEquals(
            listOf("wss://popular.example", "wss://rare.example"),
            result.candidates.map { it.url },
        )
    }

    @Test
    fun relaySearchMatchesUrlAndMetadataName() {
        val entries = listOf(
            RelayEntry("wss://yabu.me", enabled = true),
            RelayEntry("wss://r.kojira.io", enabled = false),
        )

        val result = relaySections(
            entries = entries,
            pendingRemovals = emptySet(),
            usageCounts = emptyMap(),
            searchQuery = "KOJIRELAY",
            relayNames = mapOf("wss://r.kojira.io" to "kojirelay"),
        )

        assertEquals(emptyList(), result.active)
        assertEquals(listOf("wss://r.kojira.io"), result.candidates.map { it.url })
    }

    @Test
    fun readWriteChangeOfEnabledRelayIsAnUpdate() {
        val result = calculatePublishedRelayListChanges(
            changedUrls = setOf("wss://existing.example"),
            committedEntries = listOf(RelayEntry("wss://existing.example", enabled = true)),
            draftEntries = listOf(RelayEntry("wss://existing.example", enabled = true, write = false)),
        )

        assertEquals(
            PublishedRelayListChanges(
                additions = emptySet(),
                removals = emptySet(),
                updates = setOf("wss://existing.example"),
            ),
            result,
        )
    }

    @Test
    fun disablingAnUnsavedRelayCancelsTheAddition() {
        val committed = listOf(RelayEntry("wss://existing.example", enabled = true))
        val added = committed + RelayEntry("wss://new.example", enabled = true)

        val draft = setRelayEnabledInDraft(
            draftEntries = added,
            committedEntries = committed,
            url = "wss://new.example",
            enabled = false,
        )
        val result = calculatePublishedRelayListChanges(
            changedUrls = setOf("wss://new.example"),
            committedEntries = committed,
            draftEntries = draft,
        )

        assertEquals(committed, draft)
        assertEquals(PublishedRelayListChanges(emptySet(), emptySet()), result)
    }

    @Test
    fun disabledUnsavedRelayIsNeverPublishedAsAnAddition() {
        val result = calculatePublishedRelayListChanges(
            changedUrls = setOf("wss://new.example"),
            committedEntries = emptyList(),
            draftEntries = listOf(RelayEntry("wss://new.example", enabled = false)),
        )

        assertEquals(PublishedRelayListChanges(emptySet(), emptySet()), result)
    }

    @Test
    fun disablingASavedRelayKeepsItAsAPendingRemoval() {
        val committed = listOf(RelayEntry("wss://existing.example", enabled = true))

        val draft = setRelayEnabledInDraft(
            draftEntries = committed,
            committedEntries = committed,
            url = "wss://existing.example",
            enabled = false,
        )

        assertEquals(listOf(RelayEntry("wss://existing.example", enabled = false)), draft)
    }

    @Test
    fun changesThatLeaveNoWritableRelayCannotBePublished() {
        val state = PublishedRelayListUiState(
            hasCompletedInitialFetch = true,
            pendingUpdates = setOf("wss://existing.example"),
            hasWritableRelay = false,
        )

        assertFalse(state.canPublishChanges)
        assertTrue(state.copy(hasWritableRelay = true).canPublishChanges)
    }

    @Test
    fun relayInputBecomesUrlOnlyWhenItLooksLikeARelay() {
        assertEquals("wss://relay.example.com", relayUrlFromInput(" relay.example.com "))
        assertEquals("wss://relay.example.com", relayUrlFromInput("wss://relay.example.com/"))
        assertEquals("ws://localhost:7777", relayUrlFromInput("ws://localhost:7777"))
        assertEquals(null, relayUrlFromInput("yabu"))
        assertEquals(null, relayUrlFromInput(""))
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
