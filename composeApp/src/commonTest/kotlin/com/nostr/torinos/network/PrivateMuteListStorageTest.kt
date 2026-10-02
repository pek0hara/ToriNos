package com.nostr.torinos.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateMuteListStorageTest {
    @Test
    fun legacyListsAreMigratedOnceAndRemainWithAWhenSwitchingBack() = runTest {
        val values = mutableMapOf("muted_pubkeys" to "[\"${"a".repeat(64)}\"]", "ng_words" to "[\"legacy\"]")
        val storage = storage(values)
        val a = storage.load("A")
        assertEquals(listOf("legacy"), a.ngWords)
        assertNull(values["muted_pubkeys"])
        assertNull(values["ng_words"])
        assertEquals(PrivateMuteListCache(), storage.load("B"))
        assertEquals(a, storage.load("A"))
    }

    @Test
    fun interruptedMigrationReservesOwnerAndKeepsLegacyUntilSaveSucceeds() = runTest {
        val values = mutableMapOf("ng_words" to "[\"legacy\"]")
        var failSave = true
        val storage = PrivateMuteListStorage(values::get) { key, value ->
            if (key == "private_mute_list_cache_v2_A" && failSave) error("disk failure")
            if (value == null) values.remove(key) else values[key] = value
        }
        assertFailsWith<IllegalStateException> { storage.load("A") }
        assertEquals("A", values[PrivateMuteListStorage.MIGRATION_OWNER_KEY])
        assertEquals("[\"legacy\"]", values["ng_words"])
        assertEquals(PrivateMuteListCache(), storage.load("B"))
        failSave = false
        assertEquals(listOf("legacy"), storage.load("A").ngWords)
        assertNull(values["ng_words"])
    }

    @Test
    fun existingAccountCacheWinsAndAllLegacyKeysAreRemoved() = runTest {
        val values = mutableMapOf(
            "private_mute_list_cache_v2_A" to "{\"ngWords\":[\"account\"]}",
            "private_mute_list_cache_v1" to "{\"ngWords\":[\"legacy-v1\"]}",
            "muted_pubkeys" to "[]", "ng_words" to "[\"legacy\"]",
        )
        val storage = storage(values)
        assertEquals(listOf("account"), storage.load("A").ngWords)
        assertNull(values["private_mute_list_cache_v1"])
        assertNull(values["muted_pubkeys"])
        assertNull(values["ng_words"])
        assertEquals(emptyList(), storage.load("B").ngWords)
    }

    @Test
    fun oldCacheWithoutPendingFieldsRemainsReadable() {
        assertEquals(PrivateMuteListCache(ngWords = listOf("old")),
            Json.decodeFromString<PrivateMuteListCache>("{\"ngWords\":[\"old\"]}"))
    }

    @Test
    fun oldUnsyncedLocalCacheIsRecognizedAsPendingAfterUpgrade() = runTest {
        val values = mutableMapOf(
            "private_mute_list_cache_v2_A" to "{\"ngWords\":[\"local\"],\"updatedAt\":123}",
        )
        val cache = storage(values).load("A")
        assertEquals(listOf("local"), cache.ngWords)
        assertTrue(cache.hasPendingChanges)
    }

    private fun storage(values: MutableMap<String, String>) = PrivateMuteListStorage(values::get) { key, value ->
        if (value == null) values.remove(key) else values[key] = value
    }
}
