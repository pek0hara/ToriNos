package com.nostr.torinos.ui.timeline

import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionOwnerTest {
    @Test
    fun replaceClosesThePreviousSessionBeforeOwningTheNext(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val owner = SubscriptionOwner()
        val slot = SubscriptionSlot("root")
        val first = FakeSession("first", calls)
        val second = FakeSession("second", calls)

        owner.replace(slot, first)
        owner.replace(slot, second)
        owner.close(slot)

        assertEquals(listOf("close:first", "close:second"), calls)
    }

    @Test
    fun closeAllIsIdempotentAndClosesEverySlot(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val owner = SubscriptionOwner()
        owner.replace(SubscriptionSlot("root"), FakeSession("root", calls))
        owner.replace(SubscriptionSlot("replies"), FakeSession("replies", calls))

        owner.closeAll()
        owner.closeAll()

        assertEquals(setOf("close:root", "close:replies"), calls.toSet())
        assertEquals(2, calls.size)
    }

    @Test
    fun replacingWithTheSameSessionDoesNotCloseIt(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val owner = SubscriptionOwner()
        val slot = SubscriptionSlot("root")
        val session = FakeSession("root", calls)

        owner.replace(slot, session)
        owner.replace(slot, session)

        assertEquals(emptyList(), calls)
        owner.closeAll()
        assertEquals(listOf("close:root"), calls)
    }

    @Test
    fun closeAllAttemptsEverySessionWhenOneCloseFails(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val owner = SubscriptionOwner()
        owner.replace(SubscriptionSlot("failing"), FakeSession("failing", calls, failsOnClose = true))
        owner.replace(SubscriptionSlot("healthy"), FakeSession("healthy", calls))

        runCatching { owner.closeAll() }

        assertEquals(setOf("close:failing", "close:healthy"), calls.toSet())
        assertEquals(2, calls.size)
    }

    private class FakeSession(
        override val id: String,
        private val calls: MutableList<String>,
        private val failsOnClose: Boolean = false,
    ) : SubscriptionSession {
        override val signals: Flow<SubscriptionSignal> = emptyFlow()

        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit

        override suspend fun close() {
            calls += "close:$id"
            if (failsOnClose) error("close failed: $id")
        }
    }
}
