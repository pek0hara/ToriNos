package com.nostr.torinos.emoji

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EmojiPreferencesReducerTest {
    private val catsAddress = EmojiSetAddress("a".repeat(64), "cats")
    private val dogsAddress = EmojiSetAddress("b".repeat(64), "dogs")
    private val kusaA = CustomEmoji("kusa", "https://example.com/a.png")
    private val kusaB = CustomEmoji("kusa", "https://example.com/b.png")
    private val cat = CustomEmoji("cat", "https://example.com/cat.png")
    private val cats = RegisteredEmojiSet(catsAddress, "Cats", listOf(cat, kusaA))
    private val dogs = RegisteredEmojiSet(dogsAddress, "Dogs", listOf(kusaB))

    @Test
    fun sameShortcodeFromDifferentSetsStaysAvailable() {
        val preferences = EmojiPreferences().registerSet(cats).registerSet(dogs)

        assertEquals(listOf(cat, kusaA, kusaB), preferences.available)
        assertEquals(kusaA, preferences.resolve(":kusa:"))
    }

    @Test
    fun favoritesWinShortcodeResolution() {
        val preferences = EmojiPreferences().registerSet(cats).toggleFavorite(kusaB)

        assertEquals(kusaB, preferences.resolve("kusa"))
        assertTrue(preferences.isFavorite(CustomEmoji(" :kusa: ", "https://example.com/b.png ")))
    }

    @Test
    fun unregisterRemovesOnlyTheSetWithThatAddress() {
        val preferences = EmojiPreferences()
            .registerSet(cats)
            .registerSet(RegisteredEmojiSet(dogsAddress, "Dogs", listOf(cat)))
            .unregisterSet(catsAddress)

        assertEquals(listOf(dogsAddress), preferences.sets.map { it.address })
        assertEquals(listOf(cat), preferences.available)
    }

    @Test
    fun unregisteringUnknownAddressDoesNothing() {
        val preferences = EmojiPreferences().registerSet(cats)

        assertSame(preferences, preferences.unregisterSet(dogsAddress))
    }

    @Test
    fun registerReplacesSameAddressInPlaceAndBumpsRevision() {
        val first = EmojiPreferences().registerSet(cats).registerSet(dogs)
        val updated = first.registerSet(cats.copy(title = "Cats v2", emojis = listOf(cat)))

        assertEquals(listOf("Cats v2", "Dogs"), updated.sets.map { it.title })
        assertEquals(first.revision + 1, updated.revision)
        assertSame(updated, updated.registerSet(cats.copy(title = "Cats v2", emojis = listOf(cat))))
    }

    @Test
    fun emptySetIsNotRegistered() {
        val preferences = EmojiPreferences()

        assertSame(preferences, preferences.registerSet(cats.copy(emojis = listOf(CustomEmoji("", "x")))))
    }

    @Test
    fun recordUseMovesToFrontWithoutChangingRevision() {
        val preferences = EmojiPreferences()
            .recordUse(RecentReaction.Unicode("👍"))
            .recordUse(RecentReaction.Custom(cat))
            .recordUse(RecentReaction.Unicode(" 👍 "))

        assertEquals(listOf(RecentReaction.Unicode("👍"), RecentReaction.Custom(cat)), preferences.recent)
        assertEquals(0, preferences.revision)
    }

    @Test
    fun recordUseKeepsAtMost24Entries() {
        val preferences = (1..30).fold(EmojiPreferences()) { acc, i -> acc.recordUse(RecentReaction.Unicode("$i")) }

        assertEquals(MAX_RECENT_REACTIONS, preferences.recent.size)
        assertEquals(RecentReaction.Unicode("30"), preferences.recent.first())
    }

    @Test
    fun remoteIsIgnoredWhileLocalChangesAreUnsent() {
        val local = EmojiPreferences().registerSet(cats)

        assertTrue(local.hasUnsyncedChanges)
        assertSame(local, local.applyRemote(listOf(cat), listOf(dogs), emptySet()))
    }

    @Test
    fun remoteReplacesSyncedStateAndKeepsUnresolvedAndEmptySets() {
        val local = EmojiPreferences().registerSet(cats).let { it.markSynced(it.revision) }
        val unresolved = EmojiSetAddress("c".repeat(64), "missing")
        val empty = RegisteredEmojiSet(EmojiSetAddress("d".repeat(64), "empty"), "Empty", emptyList())

        val applied = local.applyRemote(listOf(kusaB), listOf(dogs, empty), setOf(unresolved))

        assertEquals(listOf(dogsAddress), applied.sets.map { it.address })
        assertEquals(listOf(kusaB), applied.favorites)
        assertEquals(setOf(unresolved, empty.address), applied.unresolvedSetAddresses)
        assertFalse(applied.hasUnsyncedChanges)
    }

    @Test
    fun markSyncedDoesNotHideLaterChanges() {
        val sent = EmojiPreferences().registerSet(cats)
        val changedWhileSending = sent.toggleFavorite(cat)

        val synced = changedWhileSending.markSynced(sent.revision)

        assertEquals(sent.revision, synced.syncedRevision)
        assertTrue(synced.hasUnsyncedChanges)
    }
}
