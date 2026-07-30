package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertEquals

class RelayStoreTest {
    @Test
    fun publishedRelayListEnablesPublishedUrlsAndDisablesOthers() {
        val current = listOf(
            RelayEntry("wss://existing.example", enabled = true),
            RelayEntry("wss://shared.example", enabled = false),
        )

        val result = mergeRelayEntriesFromPublishedList(
            currentEntries = current,
            publishedUrls = listOf(
                " wss://shared.example ",
                "wss://new.example",
                "wss://new.example",
            ),
        )

        assertEquals(
            listOf(
                RelayEntry("wss://shared.example", enabled = true),
                RelayEntry("wss://new.example", enabled = true),
                RelayEntry("wss://existing.example", enabled = false),
            ),
            result,
        )
    }

    @Test
    fun emptyOrInvalidPublishedRelayListKeepsCurrentSettings() {
        val current = listOf(RelayEntry("wss://existing.example", enabled = true))

        assertEquals(current, mergeRelayEntriesFromPublishedList(current, emptyList()))
        assertEquals(
            current,
            mergeRelayEntriesFromPublishedList(current, listOf("", "https://not-a-relay.example")),
        )
    }

    @Test
    fun discoveredRelaysAreAppendedDisabledWithoutChangingExistingEntries() {
        val current = listOf(
            RelayEntry("wss://enabled.example", enabled = true),
            RelayEntry("wss://disabled.example", enabled = false),
        )

        val result = mergeDiscoveredRelayEntries(
            currentEntries = current,
            discoveredUrls = listOf(
                " wss://new.example ",
                "wss://enabled.example",
                "https://invalid.example",
                "wss://new.example",
            ),
        )

        assertEquals(
            current + RelayEntry("wss://new.example", enabled = false),
            result,
        )
    }

    @Test
    fun invalidOrKnownDiscoveredRelaysKeepCurrentSettings() {
        val current = listOf(RelayEntry("wss://existing.example", enabled = true))

        assertEquals(
            current,
            mergeDiscoveredRelayEntries(
                currentEntries = current,
                discoveredUrls = listOf("", "https://invalid.example", "wss://existing.example"),
            ),
        )
    }
}
