package com.nostr.torinos.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CachePersistenceTest {
    private val delay = CachePersistence.WRITE_DELAY_MS

    @Test
    fun changeMadeWhileWritingIsWrittenNext() = runTest {
        val version = MutableStateFlow(0L)
        val written = mutableListOf<String>()
        var encodes = 0
        val storage = object : LocalTextStore {
            override suspend fun read(): String? = null
            override suspend fun write(content: String) { written += content }
        }
        CachePersistence(
            name = "test", storage = storage, scope = backgroundScope, accountPubkeys = emptyFlow(), pin = {},
            version = version, restore = {},
            // The first write races with a change: it must not swallow it.
            encode = { encodes++; if (encodes == 1) version.value++; "v${version.value}" },
        ).start()
        runCurrent()

        version.value++
        advanceTimeBy(delay); runCurrent()
        assertEquals(listOf("v2"), written)

        advanceTimeBy(delay); runCurrent()
        assertEquals(listOf("v2", "v2"), written)

        advanceTimeBy(delay * 3); runCurrent()
        assertEquals(2, written.size)
    }
}
