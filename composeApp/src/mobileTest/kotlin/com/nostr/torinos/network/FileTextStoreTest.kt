package com.nostr.torinos.network

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FileTextStoreTest {
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "text-store-test-${Random.nextLong()}"
    private val path = directory / "cache.json"

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    @Test
    fun readsNullBeforeFirstWriteAndLatestContentAfterOverwrite() = runTest {
        val store = FileTextStore(path)
        assertNull(store.read())

        store.write("first")
        store.write("プロフィール")

        assertEquals("プロフィール", store.read())
        assertFalse(FileSystem.SYSTEM.exists(directory / "cache.json.tmp"))
    }
}
