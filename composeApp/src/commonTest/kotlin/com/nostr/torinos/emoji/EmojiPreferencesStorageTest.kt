package com.nostr.torinos.emoji

import com.nostr.torinos.network.CustomEmoji
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class EmojiPreferencesStorageTest {
    private val pubkey = "f".repeat(64)
    private val author = "a".repeat(64)
    private val cat = CustomEmoji("cat", "https://example.com/cat.png")
    private val star = CustomEmoji("star", "https://example.com/star.png")
    private val manual = CustomEmoji("mine", "https://example.com/mine.png")

    private class FakeStore(initial: Map<String, String> = emptyMap()) : EmojiKeyValueStore {
        val values = initial.toMutableMap()
        override suspend fun get(key: String): String? = values[key]
        override suspend fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun roundTripsV2() = runTest {
        val store = FakeStore()
        val storage = EmojiPreferencesStorage(store)
        val preferences = EmojiPreferences()
            .registerSet(RegisteredEmojiSet(EmojiSetAddress(author, "cats"), "Cats", listOf(cat)))
            .toggleFavorite(star)
            .recordUse(RecentReaction.Custom(cat))
            .recordUse(RecentReaction.Unicode("🎉"))
            .copy(unresolvedSetAddresses = setOf(EmojiSetAddress(author, "missing")))

        storage.save(pubkey, preferences)

        assertEquals(preferences, storage.load(pubkey))
    }

    @Test
    fun migratesScopedLegacyKeysAndMergesManualListIntoFavorites() = runTest {
        val store = FakeStore(
            mapOf(
                "custom_emoji_lists_$pubkey" to """[
                    {"id":"$author:cats","name":"Cats","emojis":[{"shortcode":"cat","imageUrl":"${cat.imageUrl}"}],"authorPubkey":"$author"},
                    {"id":"manual","name":"カスタム絵文字","emojis":[{"shortcode":"mine","imageUrl":"${manual.imageUrl}"}]}
                ]""",
                "favorite_custom_emojis_$pubkey" to """[{"shortcode":"star","imageUrl":"${star.imageUrl}"}]""",
                "recent_reactions_$pubkey" to """[
                    {"kind":"custom","value":"cat","imageUrl":"${cat.imageUrl}"},
                    {"kind":"unicode","value":"👍"}
                ]""",
                "custom_emojis_$pubkey" to "[]",
            ),
        )

        val loaded = EmojiPreferencesStorage(store).load(pubkey)

        assertEquals(listOf(EmojiSetAddress(author, "cats")), loaded.sets.map { it.address })
        assertEquals(listOf(star, manual), loaded.favorites)
        assertEquals(listOf(RecentReaction.Custom(cat), RecentReaction.Unicode("👍")), loaded.recent)
        assertFalse(loaded.hasUnsyncedChanges)
        assertNull(store.values["custom_emoji_lists_$pubkey"])
        assertEquals(loaded, EmojiPreferencesStorage(store).load(pubkey))
    }

    @Test
    fun migratesGlobalLegacyKeysWhenAccountHasNone() = runTest {
        val store = FakeStore(
            mapOf(
                "custom_emojis" to """[{"shortcode":"mine","imageUrl":"${manual.imageUrl}"}]""",
                "recent_custom_emojis" to """["mine","unknown"]""",
            ),
        )

        val loaded = EmojiPreferencesStorage(store).load(pubkey)

        assertEquals(listOf(manual), loaded.favorites)
        assertEquals(listOf(RecentReaction.Custom(manual)), loaded.recent)
        assertNull(store.values["custom_emojis"])
    }

    @Test
    fun emptyStoreLoadsDefaults() = runTest {
        assertEquals(EmojiPreferences(), EmojiPreferencesStorage(FakeStore()).load(pubkey))
    }
}
