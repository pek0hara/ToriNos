package com.nostr.torinos.ui.notification

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationSyncCoordinatorTest {
    private val activity = listOf(NostrFilter(kinds = listOf(1), limit = 100))
    private val follows = NostrFilter(kinds = listOf(3), limit = 100)

    @Test fun initialCompletionKeepsVisibleLiveOwned() = runTest {
        val sessions = mutableListOf<Fake>()
        val coordinator = NotificationSyncCoordinator(backgroundScope, "test", {}, {}, open = { spec ->
            Fake(spec, true).also { sessions += it }
        })
        val initial = launch { coordinator.sync(activity, follows, 100) }
        runCurrent()
        coordinator.setVisible(true, activity, follows, 100)
        initial.join()
        val live = sessions.filter { it.spec.behavior == SubscriptionBehavior.Live }
        assertEquals(2, live.size)
        assertTrue(live.none { it.closed })
        coordinator.setVisible(false, activity, follows, 100)
        assertTrue(live.all { it.closed })
        assertTrue(sessions.filter { it.spec.behavior is SubscriptionBehavior.Fetch }.all { it.closed })
        coordinator.close()
    }

    @Test fun failedRelayCannotCommitFollowerBaseline() = runTest {
        val coordinator = NotificationSyncCoordinator(backgroundScope, "test", {}, {}, open = { Fake(it, false) })
        val result = coordinator.sync(activity, follows, 100)
        assertFalse(result.canCommitFollowerBaseline)
        coordinator.close()
    }

    @Test fun limitReachedDoesNotAdvanceFollowerPosition() = runTest {
        val coordinator = NotificationSyncCoordinator(backgroundScope, "test", {}, {}, open = { Fake(it, true, full = true) })
        assertFalse(coordinator.sync(activity, follows.copy(limit = 1), 100).canCommitFollowerBaseline)
        coordinator.close()
    }

    @Test fun failedLiveStartupDoesNotKeepAnInitialOwnerAfterRetry() = runTest {
        var opens = 0
        val sessions = mutableListOf<Fake>()
        val coordinator = NotificationSyncCoordinator(backgroundScope, "test", {}, {}, open = { spec ->
            if (++opens == 2) error("second live failed")
            Fake(spec, true).also { sessions += it }
        })
        assertFailsWith<IllegalStateException> { coordinator.sync(activity, follows, 100) }
        assertTrue(sessions.all { it.closed })
        coordinator.setVisible(true, activity, follows, 100)
        assertTrue(sessions.takeLast(2).none { it.closed })
        coordinator.setVisible(false, activity, follows, 100)
        assertTrue(sessions.all { it.closed })
        coordinator.close()
    }

    private class Fake(val spec: SubscriptionSpec, private val success: Boolean, private val full: Boolean = false) : SubscriptionSession {
        override val id = spec.id
        override val expectedRelayUrls = setOf("a", "b")
        var closed = false
        override val signals: Flow<SubscriptionSignal> = flow {
            if (spec.behavior == SubscriptionBehavior.Live) awaitCancellation()
            delay(10)
            if (full && spec.filters.first().kinds == listOf(3)) {
                emit(SubscriptionSignal.Event("a", NostrEvent("id", "author", 99, 3, emptyList(), "", ""), false))
            }
            emit(SubscriptionSignal.FetchCompleted(mapOf("a" to RelayOutcome.Eose,
                "b" to if (success) RelayOutcome.Eose else RelayOutcome.TimedOut), !success))
        }
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() { closed = true }
    }
}
