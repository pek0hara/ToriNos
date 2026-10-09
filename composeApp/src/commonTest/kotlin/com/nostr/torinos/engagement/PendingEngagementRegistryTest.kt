package com.nostr.torinos.engagement

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PendingEngagementRegistryTest {
    private val like = PendingEngagementOperation(
        id = EngagementOperationId("op-1"),
        request = EngagementRequest.AddLike,
        appliedDelta = EngagementDelta(reactionCount = 1, likeReactionCount = 1),
    )

    @Test
    fun operationsAreSharedWithinTheSameAccount() {
        PendingEngagementRegistry.clearForTest()
        val fromFeed = PendingEngagementRegistry.forSession("session-a")
        val fromThread = PendingEngagementRegistry.forSession("session-a")

        fromFeed.put("note", EngagementSlot.Reaction, like)

        assertSame(fromFeed, fromThread)
        assertEquals(mapOf(EngagementSlot.Reaction to like), fromThread.operations("note"))
    }

    @Test
    fun accountsDoNotSeeEachOthersOperations() {
        PendingEngagementRegistry.clearForTest()
        val first = PendingEngagementRegistry.forSession("session-a")
        val second = PendingEngagementRegistry.forSession("session-b")

        first.put("note", EngagementSlot.Reaction, like)

        assertNotSame(first, second)
        assertEquals(emptyMap(), second.operations("note"))
    }

    @Test
    fun removingByOperationIdKeepsOtherSlots() {
        val pending = PendingEngagementOperations()
        val repost = PendingEngagementOperation(
            id = EngagementOperationId("op-2"),
            request = EngagementRequest.AddRepost,
            appliedDelta = EngagementDelta(repostCount = 1),
        )
        pending.put("note", EngagementSlot.Reaction, like)
        pending.put("note", EngagementSlot.Repost, repost)

        pending.remove("note", like.id)

        assertEquals(mapOf(EngagementSlot.Repost to repost), pending.operations("note"))
    }

    @Test
    fun notifiesChangedNotes() = runTest {
        val pending = PendingEngagementOperations()
        val received = mutableListOf<Set<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pending.changes.collect { received += it } }

        pending.put("note", EngagementSlot.Reaction, like)
        pending.remove("note", like.id)

        assertEquals(listOf(setOf("note"), setOf("note")), received)
    }
}
