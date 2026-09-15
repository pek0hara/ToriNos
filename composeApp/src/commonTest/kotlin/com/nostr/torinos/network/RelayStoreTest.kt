package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelayStoreTest {
    @Test
    fun emptyRelayListCannotBePublished() {
        assertFailsWith<IllegalStateException> {
            checkRelayListCanBePublished(emptyList())
        }
    }

    @Test
    fun relayListRequiresAtLeastOneWriteCapableRelay() {
        assertFailsWith<IllegalStateException> {
            checkRelayListCanBePublished(
                listOf(listOf("r", "wss://read.example", "read")),
            )
        }

        checkRelayListCanBePublished(
            listOf(
                listOf("r", "wss://read.example", "read"),
                listOf("r", "wss://write.example", "write"),
            ),
        )
    }

    @Test
    fun publishedRelayListEnablesPublishedUrlsAndDisablesOthers() {
        val current = listOf(
            RelayEntry("wss://existing.example", enabled = true),
            RelayEntry("wss://shared.example", enabled = false),
        )

        val result = mergeRelayEntriesFromPublishedList(
            currentEntries = current,
            publishedEntries = listOf(
                RelayEntry(" wss://shared.example ", enabled = true),
                RelayEntry("wss://new.example", enabled = true),
                RelayEntry("wss://new.example", enabled = true),
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
            mergeRelayEntriesFromPublishedList(
                current,
                listOf(
                    RelayEntry("", enabled = true),
                    RelayEntry("https://not-a-relay.example", enabled = true),
                ),
            ),
        )
    }

    @Test
    fun nip65ReadWriteMarkersAreKeptAndUsedForRouting() {
        val published = relayEntriesFromTags(
            listOf(
                listOf("r", "wss://read.example", "read"),
                listOf("r", "wss://write.example", "write"),
                listOf("r", "wss://both.example"),
            ),
        )

        assertEquals(
            listOf(
                RelayEntry("wss://read.example", enabled = true, read = true, write = false),
                RelayEntry("wss://write.example", enabled = true, read = false, write = true),
                RelayEntry("wss://both.example", enabled = true, read = true, write = true),
            ),
            published,
        )
        assertEquals(
            listOf("wss://read.example", "wss://both.example"),
            readableRelayUrls(published),
        )
        assertEquals(
            listOf("wss://write.example", "wss://both.example"),
            writableRelayUrls(published),
        )
    }

    @Test
    fun publishedDirectionsReplacePreviousLocalDirections() {
        val current = listOf(
            RelayEntry("wss://read.example", enabled = true),
            RelayEntry("wss://write.example", enabled = false),
        )

        val result = mergeRelayEntriesFromPublishedList(
            currentEntries = current,
            publishedEntries = relayEntriesFromTags(
                listOf(
                    listOf("r", "wss://read.example", "read"),
                    listOf("r", "wss://write.example", "write"),
                ),
            ),
        )

        assertEquals(
            listOf(
                RelayEntry("wss://read.example", enabled = true, read = true, write = false),
                RelayEntry("wss://write.example", enabled = true, read = false, write = true),
            ),
            result,
        )
    }

    @Test
    fun duplicateReadAndWriteTagsAreMergedForSameRelay() {
        assertEquals(
            listOf(RelayEntry("wss://relay.example", enabled = true)),
            relayEntriesFromTags(
                listOf(
                    listOf("r", "wss://relay.example", "read"),
                    listOf("r", "wss://relay.example", "write"),
                ),
            ),
        )
    }

    @Test
    fun savedEntriesWithoutDirectionsRemainReadWriteCapable() {
        assertEquals(
            RelayEntry("wss://legacy.example", enabled = true, read = true, write = true),
            Json.decodeFromString<RelayEntry>(
                """{"url":"wss://legacy.example","enabled":true}""",
            ),
        )
    }

    @Test
    fun explicitPublishingRejectsKnownReadOnlyRelayButAllowsUnknownRelay() {
        val entries = listOf(
            RelayEntry("wss://read.example", enabled = true, read = true, write = false),
            RelayEntry("wss://write.example", enabled = true, read = false, write = true),
        )

        assertEquals(false, relayAllowsWriting(entries, "wss://read.example"))
        assertEquals(true, relayAllowsWriting(entries, "wss://write.example"))
        assertEquals(true, relayAllowsWriting(entries, "wss://hint.example"))
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

    @Test
    fun relayListChangesPreserveUntouchedTagsAndReadWriteMarkers() {
        val currentTags = listOf(
            listOf("r", "wss://read.example", "read"),
            listOf("r", "wss://remove.example", "write"),
            listOf("alt", "relay list metadata"),
        )

        val result = applyRelayListChanges(
            currentTags = currentTags,
            additions = setOf(RelayEntry("wss://new.example", enabled = true)),
            removals = setOf("wss://remove.example"),
        )

        assertEquals(
            listOf(
                listOf("r", "wss://read.example", "read"),
                listOf("alt", "relay list metadata"),
                listOf("r", "wss://new.example"),
            ),
            result,
        )
    }

    @Test
    fun relayListChangesAreIdempotentAndRemovalWins() {
        val currentTags = listOf(listOf("r", "wss://existing.example"))

        val result = applyRelayListChanges(
            currentTags = currentTags,
            additions = setOf(
                RelayEntry("wss://existing.example", enabled = true),
                RelayEntry("wss://same.example", enabled = true),
            ),
            removals = setOf("wss://same.example"),
        )

        assertEquals(currentTags, result)
    }

    @Test
    fun reEnablingRelayPublishesItsStoredDirection() {
        val result = applyRelayListChanges(
            currentTags = listOf(listOf("alt", "relay list metadata")),
            additions = listOf(
                RelayEntry("wss://read.example", enabled = true, read = true, write = false),
                RelayEntry("wss://write.example", enabled = true, read = false, write = true),
            ),
            removals = emptySet(),
        )

        assertEquals(
            listOf(
                listOf("alt", "relay list metadata"),
                listOf("r", "wss://read.example", "read"),
                listOf("r", "wss://write.example", "write"),
            ),
            result,
        )
    }

    @Test
    fun legacyKind3RelayConfigurationCanSeedNip65List() {
        val event = NostrEvent(
            id = "id",
            pubkey = "pubkey",
            createdAt = 100,
            kind = 3,
            tags = emptyList(),
            content = """{"wss://both.example":{"read":true,"write":true},"wss://read.example":{"read":true,"write":false},"wss://off.example":{"read":false,"write":false}}""",
            sig = "sig",
        )

        assertEquals(
            listOf(
                listOf("r", "wss://both.example"),
                listOf("r", "wss://read.example", "read"),
            ),
            legacyRelayTagsFromContactEvent(event),
        )
    }

}
