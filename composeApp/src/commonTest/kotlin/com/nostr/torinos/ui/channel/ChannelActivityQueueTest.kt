package com.nostr.torinos.ui.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChannelActivityQueueTest {
    @Test
    fun limitsConcurrencyAndReprioritizesWaitingChannels() {
        val queue = ChannelActivityQueue()
        queue.enqueue(listOf("a", "b", "c", "d", "e", "f"))
        queue.prioritize(setOf("e"), setOf("f"))
        assertEquals("f", queue.takeNext())
        assertEquals("e", queue.takeNext())
        assertEquals("a", queue.takeNext())
        assertNull(queue.takeNext())
        queue.prioritize(emptySet(), setOf("d"))
        queue.complete("f")
        assertEquals("d", queue.takeNext())
        assertNull(queue.takeNext())
    }

    @Test
    fun cacheUpdatesDoNotRepeatCompletedOrActiveFetches() {
        val queue = ChannelActivityQueue()
        queue.enqueue(listOf("a", "b"))
        assertEquals("a", queue.takeNext())
        queue.complete("a")
        assertEquals("b", queue.takeNext())
        queue.enqueue(listOf("a", "b"))
        assertNull(queue.takeNext())
    }

    @Test
    fun stoppingPreventsQueuedFetchesFromStartingDuringCleanup() {
        val queue = ChannelActivityQueue(1)
        queue.enqueue(listOf("a", "b"))
        assertEquals("a", queue.takeNext())
        queue.stop()
        queue.complete("a")
        queue.enqueue(listOf("c"))
        assertNull(queue.takeNext())
    }
}
