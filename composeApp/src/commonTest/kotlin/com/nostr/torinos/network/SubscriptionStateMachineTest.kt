package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SubscriptionStateMachineTest {
    private val oldFilters = listOf(NostrFilter(kinds = listOf(1), since = 100L))
    private val newFilters = listOf(NostrFilter(kinds = listOf(1), since = 200L))

    @Test
    fun initialSubscriptionOpensRequest() {
        val result = SubscriptionStateMachine.reconcile(null, oldFilters, 1L)

        assertEquals(RelaySubscriptionPhase.Sent, result.state?.phase)
        assertEquals(oldFilters, assertIs<SubscriptionCommandDecision.Open>(result.command).filters)
    }

    @Test
    fun updateWaitsUntilEose() {
        val sent = SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!
        val pending = SubscriptionStateMachine.reconcile(sent, newFilters, 1L)

        assertNull(pending.command)
        assertEquals(oldFilters, pending.state?.sentFilters)

        val live = SubscriptionStateMachine.onEose(pending.state!!)
        val updated = SubscriptionStateMachine.reconcile(live, newFilters, 1L)

        assertEquals(newFilters, assertIs<SubscriptionCommandDecision.Open>(updated.command).filters)
    }

    @Test
    fun targetRemovalClosesSentSubscription() {
        val sent = SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!
        val result = SubscriptionStateMachine.reconcile(sent, null, 1L)

        assertIs<SubscriptionCommandDecision.Close>(result.command)
        assertEquals(RelaySubscriptionPhase.Closing, result.state?.phase)
    }

    @Test
    fun reconnectResendsLatestDesiredFilters() {
        val sent = SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!
        val disconnected = SubscriptionStateMachine.onDisconnected(sent, 2L)
        val result = SubscriptionStateMachine.reconcile(disconnected, newFilters, 2L)

        assertEquals(newFilters, assertIs<SubscriptionCommandDecision.Open>(result.command).filters)
        assertEquals(2L, result.state?.connectionGeneration)
    }

    @Test
    fun repeatedStructuralRefusalSuppressesSameFilter() {
        var state = SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!
        repeat(3) {
            state = SubscriptionStateMachine.onClosed(state, structural = true, maxStructuralRefusals = 3)
            if (it < 2) {
                state = SubscriptionStateMachine.prepareRetry(state)
                state = SubscriptionStateMachine.reconcile(state, oldFilters, 1L).state!!
            }
        }

        assertEquals(RelaySubscriptionPhase.Suppressed, state.phase)
        assertNull(SubscriptionStateMachine.reconcile(state, oldFilters, 1L).command)
        assertIs<SubscriptionCommandDecision.Open>(
            SubscriptionStateMachine.reconcile(state, newFilters, 1L).command,
        )
    }

    @Test
    fun closedReasonsChooseSafeRetryPolicy() {
        assertEquals(
            RetryDisposition.RetryAfterAuth,
            classifyClosedReason("auth-required: challenge").disposition,
        )
        assertEquals(
            RetryDisposition.RetryWithBackoff,
            classifyClosedReason("rate-limited: slow down").disposition,
        )
        assertEquals(
            RetryDisposition.RetryOnFilterChange,
            classifyClosedReason("unsupported: filter").disposition,
        )
        assertEquals(
            RetryDisposition.DoNotRetry,
            classifyClosedReason("invalid: malformed").disposition,
        )
    }

    @Test
    fun relaysCanReachEoseInAnyOrderWithoutChangingEachOthersState() {
        val relayA = SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!
        val relayB = SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!

        val bCompletedFirst = SubscriptionStateMachine.onEose(relayB)
        assertEquals(RelaySubscriptionPhase.Sent, relayA.phase)
        assertEquals(RelaySubscriptionPhase.Live, bCompletedFirst.phase)

        val aCompletedLater = SubscriptionStateMachine.onEose(relayA)
        assertEquals(RelaySubscriptionPhase.Live, aCompletedLater.phase)
        assertEquals(RelaySubscriptionPhase.Live, bCompletedFirst.phase)
    }

    @Test
    fun disconnectWhileLiveRecordsInterruptionUntilNextEose() {
        val sent = SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!
        val live = SubscriptionStateMachine.onEose(sent)

        val disconnected = SubscriptionStateMachine.onDisconnected(live, 2L, disconnectedAt = 500L)
        assertEquals(500L, disconnected.interruptedAt)

        // 再接続を繰り返しても、最初に途切れた時刻を保つ
        val resent = SubscriptionStateMachine.reconcile(disconnected, oldFilters, 2L).state!!
        val disconnectedAgain = SubscriptionStateMachine.onDisconnected(resent, 3L, disconnectedAt = 900L)
        assertEquals(500L, disconnectedAgain.interruptedAt)

        val resumed = SubscriptionStateMachine.onEose(
            SubscriptionStateMachine.reconcile(disconnectedAgain, oldFilters, 3L).state!!,
        )
        assertNull(resumed.interruptedAt)
    }

    @Test
    fun disconnectDuringFilterUpdateAfterLiveIsAnInterruption() {
        val live = SubscriptionStateMachine.onEose(SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!)
        val updating = SubscriptionStateMachine.reconcile(live, newFilters, 1L).state!!
        assertEquals(RelaySubscriptionPhase.Sent, updating.phase)

        val disconnected = SubscriptionStateMachine.onDisconnected(updating, 2L, disconnectedAt = 500L)

        assertEquals(500L, disconnected.interruptedAt)
    }

    @Test
    fun replayReachingTheInterruptionLeavesNoGap() {
        val interrupted = interruptedAndResent(interruptedAt = 500L)
        val replayed = listOf(900L, 700L, 450L).fold(interrupted) { state, createdAt ->
            SubscriptionStateMachine.onEvent(state, event(createdAt))
        }

        assertNull(SubscriptionStateMachine.replayGap(replayed))
    }

    @Test
    fun replayCutByRelayLimitReportsTheMissingRange() {
        val interrupted = interruptedAndResent(interruptedAt = 500L)
        val replayed = listOf(900L, 800L).fold(interrupted) { state, createdAt ->
            SubscriptionStateMachine.onEvent(state, event(createdAt))
        }

        assertEquals(ReplayGap(interruptedAt = 500L, replayOldestAt = 800L), SubscriptionStateMachine.replayGap(replayed))
        assertNull(SubscriptionStateMachine.onEose(replayed).interruptedAt)
    }

    @Test
    fun truncatedFilterIsDetectedEvenWhenAnotherFilterReachesBack() {
        val filters = listOf(
            NostrFilter(kinds = listOf(1), since = 100L),
            NostrFilter(kinds = listOf(1111), since = 100L),
        )
        val live = SubscriptionStateMachine.onEose(SubscriptionStateMachine.reconcile(null, filters, 1L).state!!)
        val disconnected = SubscriptionStateMachine.onDisconnected(live, 2L, disconnectedAt = 500L)
        var state = SubscriptionStateMachine.reconcile(disconnected, filters, 2L).state!!
        // 投稿は上限で途切れた後だけ、返信は途切れる前まで届いた
        state = SubscriptionStateMachine.onEvent(state, event(900L, kind = 1))
        state = SubscriptionStateMachine.onEvent(state, event(800L, kind = 1))
        state = SubscriptionStateMachine.onEvent(state, event(300L, kind = 1111))

        assertEquals(ReplayGap(interruptedAt = 500L, replayOldestAt = 800L), SubscriptionStateMachine.replayGap(state))
    }

    @Test
    fun filtersSharingAKindAreToldApartByTheirConditions() {
        val filters = listOf(
            NostrFilter(kinds = listOf(1), authors = listOf("followee"), since = 100L),
            NostrFilter(kinds = listOf(1), tTags = listOf("nostr"), since = 100L),
        )
        val live = SubscriptionStateMachine.onEose(SubscriptionStateMachine.reconcile(null, filters, 1L).state!!)
        val disconnected = SubscriptionStateMachine.onDisconnected(live, 2L, disconnectedAt = 500L)
        var state = SubscriptionStateMachine.reconcile(disconnected, filters, 2L).state!!
        // フォロー中の投稿は途切れる前まで届き、ハッシュタグ側は上限で切れた
        state = SubscriptionStateMachine.onEvent(state, event(300L, pubkey = "followee"))
        state = SubscriptionStateMachine.onEvent(state, event(900L, pubkey = "stranger", tags = listOf(listOf("t", "nostr"))))

        assertEquals(ReplayGap(interruptedAt = 500L, replayOldestAt = 900L), SubscriptionStateMachine.replayGap(state))
    }

    private fun event(
        createdAt: Long,
        kind: Int = 1,
        pubkey: String = "author",
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(id = "e$createdAt$pubkey", pubkey = pubkey, createdAt = createdAt, kind = kind, tags = tags, content = "", sig = "")

    @Test
    fun sinceAdvancedDuringOutageIsReportedAsGap() {
        val live = SubscriptionStateMachine.onEose(SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!)
        val disconnected = SubscriptionStateMachine.onDisconnected(live, 2L, disconnectedAt = 150L)
        // 切断中に since=200 のフィルターへ更新され、再送では 150〜200 を取れない
        val resent = SubscriptionStateMachine.reconcile(disconnected, newFilters, 2L).state!!

        assertEquals(ReplayGap(interruptedAt = 150L, replayOldestAt = 200L), SubscriptionStateMachine.replayGap(resent))
    }

    @Test
    fun emptyReplayLeavesNoGap() {
        assertNull(SubscriptionStateMachine.replayGap(interruptedAndResent(interruptedAt = 500L)))
    }

    private fun interruptedAndResent(interruptedAt: Long): RelaySubscriptionState {
        val live = SubscriptionStateMachine.onEose(SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!)
        val disconnected = SubscriptionStateMachine.onDisconnected(live, 2L, disconnectedAt = interruptedAt)
        return SubscriptionStateMachine.reconcile(disconnected, oldFilters, 2L).state!!
    }

    @Test
    fun disconnectBeforeFirstEoseIsNotAnInterruption() {
        val sent = SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!

        val disconnected = SubscriptionStateMachine.onDisconnected(sent, 2L, disconnectedAt = 500L)

        assertNull(disconnected.interruptedAt)
    }

    @Test
    fun transientCloseWhileLiveRecordsInterruption() {
        val live = SubscriptionStateMachine.onEose(SubscriptionStateMachine.reconcile(null, oldFilters, 1L).state!!)

        val closed = SubscriptionStateMachine.onClosed(
            live,
            structural = false,
            maxStructuralRefusals = 3,
            closedAt = 700L,
        )

        assertEquals(700L, closed.interruptedAt)
        assertEquals(700L, SubscriptionStateMachine.prepareRetry(closed).interruptedAt)
    }

    @Test
    fun liveRetryKeepsGoingAtASlowPaceAfterQuickAttempts() {
        val delays = (1..6).map {
            SubscriptionStateMachine.liveRetryDelayMillis(
                attempt = it,
                baseDelayMillis = 1_000L,
                quickAttempts = 3,
                slowDelayMillis = 300_000L,
            )
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 300_000L, 300_000L, 300_000L), delays)
    }
}
