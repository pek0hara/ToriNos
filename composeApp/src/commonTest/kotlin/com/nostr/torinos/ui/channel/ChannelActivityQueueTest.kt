package com.nostr.torinos.ui.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelActivityQueueTest {
    @Test
    fun batchesFollowPriorityAndLimitConcurrency() {
        val queue = ChannelActivityQueue(concurrency = 2)
        queue.enqueue(listOf("a", "b", "c", "d", "e", "f"))
        queue.prioritize(favorites = setOf("e"), visible = setOf("f", "d"))
        assertEquals(listOf("d", "f", "e"), queue.takeBatch(3))
        assertEquals(listOf("a", "b"), queue.takeBatch(2))
        assertTrue(queue.takeBatch(3).isEmpty())
        queue.complete(listOf("d", "f", "e"))
        assertEquals(listOf("c"), queue.takeBatch(3))
    }

    @Test
    fun reprioritizesWaitingChannels() {
        val queue = ChannelActivityQueue(concurrency = 1)
        queue.enqueue(listOf("a", "b", "c"))
        assertEquals(listOf("a"), queue.takeBatch(1))
        queue.prioritize(favorites = emptySet(), visible = setOf("c"))
        queue.complete(listOf("a"))
        assertEquals(listOf("c"), queue.takeBatch(1))
    }

    @Test
    fun cacheUpdatesDoNotRepeatCompletedOrActiveFetches() {
        val queue = ChannelActivityQueue()
        queue.enqueue(listOf("a", "b"))
        assertEquals(listOf("a", "b"), queue.takeBatch(10))
        queue.complete(listOf("a", "b"))
        queue.enqueue(listOf("a", "b"))
        assertTrue(queue.takeBatch(10).isEmpty())
    }

    @Test
    fun stoppingPreventsQueuedFetchesFromStartingDuringCleanup() {
        val queue = ChannelActivityQueue(concurrency = 1)
        queue.enqueue(listOf("a", "b"))
        assertEquals(listOf("a"), queue.takeBatch(1))
        queue.stop()
        queue.complete(listOf("a"))
        queue.enqueue(listOf("c"))
        assertTrue(queue.takeBatch(1).isEmpty())
    }

    @Test
    fun activityFiltersAskForTheLatestMessagePerChannel() {
        val filters = channelActivityFilters(listOf("a", "b")) { if (it == "a") 100L else null }
        assertEquals(2, filters.size)
        assertEquals(listOf(listOf("a"), listOf("b")), filters.map { it.eTags })
        assertEquals(listOf(100L, null), filters.map { it.since })
        assertTrue(filters.all { it.kinds == listOf(42) && it.limit == 1 })
    }
}
