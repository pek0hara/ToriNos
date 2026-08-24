package com.nostr.torinos.ui.timeline

import com.nostr.torinos.engagement.EngagementOperationId
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.NoteEngagementState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class NoteEngagementCoordinatorTest {
    private val coordinator = NoteEngagementCoordinator(null)
    private val operationId = EngagementOperationId("operation")

    @Test
    fun beginCommitAndRollbackKeepTheOperationBoundaryTyped() {
        val initial = NoteEngagementState(reactionCount = 2)
        val optimistic = coordinator.begin(initial, operationId, EngagementRequest.AddLike)

        assertEquals(3, optimistic.reactionCount)
        assertIs<EngagementRequest.AddLike>(optimistic.pendingOperations[EngagementSlot.Reaction]?.request)

        val committed = coordinator.commit(optimistic, operationId, "reaction-event")
        assertEquals("reaction-event", committed.ownLikeEventId)
        assertNull(committed.pendingOperations[EngagementSlot.Reaction])

        val rolledBack = coordinator.rollback(optimistic, operationId)
        assertEquals(initial, rolledBack)
    }
}
