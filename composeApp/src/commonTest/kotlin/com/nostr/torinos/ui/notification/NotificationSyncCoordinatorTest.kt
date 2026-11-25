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

    @Test fun updatesOpenActivityLiveKeepingItsSinceAndLeavesFollowsAlone() = runTest {
        val sessions = mutableListOf<Fake>()
        val coordinator = NotificationSyncCoordinator(backgroundScope, "test", {}, {}, open = { spec ->
            Fake(spec, true).also { sessions += it }
        })
        val now = FOLLOW_SYNC_OVERLAP_SECONDS + 500
        coordinator.setVisible(true, activity, follows, now)
        val quotes = activity + NostrFilter(kinds = listOf(1), qTags = listOf("q"), limit = 100)
        coordinator.updateActivityFilters(quotes)
        val live = sessions.filter { it.spec.behavior == SubscriptionBehavior.Live }
        assertEquals(listOf(quotes.map { it.copy(since = 500, until = null) }), live[0].updates)
        assertTrue(live[1].updates.isEmpty())
        coordinator.close()
    }

    @Test fun updateWithoutLiveDoesNothing() = runTest {
        val sessions = mutableListOf<Fake>()
        val coordinator = NotificationSyncCoordinator(backgroundScope, "test", {}, {}, open = { spec ->
            Fake(spec, true).also { sessions += it }
        })
        coordinator.updateActivityFilters(activity)
        assertTrue(sessions.isEmpty())
        coordinator.close()
    }

    @Test fun supplementalActivityFetchDeliversEvents() = runTest {
        val received = mutableListOf<NostrEvent>()
        val coordinator = NotificationSyncCoordinator(backgroundScope, "test", { received += it }, {}, open = { spec ->
            Fake(spec, true, emitted = NostrEvent("x".repeat(64), "author", 99, 1, emptyList(), "", ""))
        })
        coordinator.fetchActivity(activity, until = 100)
        assertEquals(listOf("x".repeat(64)), received.map { it.id })
        coordinator.close()
    }

    private class Fake(
        val spec: SubscriptionSpec,
        private val success: Boolean,
        private val full: Boolean = false,
        private val emitted: NostrEvent? = null,
    ) : SubscriptionSession {
        val updates = mutableListOf<List<NostrFilter>>()
        override val id = spec.id
        override val expectedRelayUrls = setOf("a", "b")
        var closed = false
        override val signals: Flow<SubscriptionSignal> = flow {
            if (spec.behavior == SubscriptionBehavior.Live) awaitCancellation()
            delay(10)
            if (full && spec.filters.first().kinds == listOf(3)) {
                emit(SubscriptionSignal.Event("a", NostrEvent("id", "author", 99, 3, emptyList(), "", ""), false))
            }
            emitted?.let { emit(SubscriptionSignal.Event("a", it, false)) }
            emit(SubscriptionSignal.FetchCompleted(mapOf("a" to RelayOutcome.Eose,
                "b" to if (success) RelayOutcome.Eose else RelayOutcome.TimedOut), !success))
        }
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) { updates += filters }
        override suspend fun close() { closed = true }
    }
}
