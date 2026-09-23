package com.nostr.torinos.ui.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeedChromeBehaviorTest {
    @Test
    fun zeroDeltaDoesNotChangeStateOrRequestSettle() {
        val state = FeedChromeBehaviorState()

        val decision = reduceFeedChromeUserScroll(
            state = state,
            delta = 0f,
            currentFraction = 0.4f,
            collapseDistancePx = 100,
        )

        assertEquals(state, decision.state)
        assertEquals(0.4f, decision.nextFraction)
        assertEquals(0f, decision.consumedY)
        assertNull(decision.requestSettleAfterMillis)
    }

    @Test
    fun firstNonZeroInputCapturesStartVisibilityOnlyOnce() {
        val first = reduceFeedChromeUserScroll(
            state = FeedChromeBehaviorState(),
            delta = -10f,
            currentFraction = 1f,
            collapseDistancePx = 100,
        )
        val second = reduceFeedChromeUserScroll(
            state = first.state,
            delta = -10f,
            currentFraction = first.nextFraction,
            collapseDistancePx = 100,
        )

        assertEquals(FeedChromeStartVisibility.FullyHidden, first.state.startVisibility)
        assertEquals(FeedChromeStartVisibility.FullyHidden, second.state.startVisibility)
        assertEquals(FeedChromeGesturePhase.RevealAllowed, second.state.gesturePhase)
    }

    @Test
    fun collapseInputUpdatesFractionConsumptionAndSettleState() {
        val decision = reduceFeedChromeUserScroll(
            state = FeedChromeBehaviorState(),
            delta = 25f,
            currentFraction = 0.25f,
            collapseDistancePx = 100,
        )

        assertEquals(FeedChromeGesturePhase.CollapseLocked, decision.state.gesturePhase)
        assertEquals(FeedChromeStartVisibility.VisibleOrPartial, decision.state.startVisibility)
        assertEquals(FeedChromeSettleBias.TowardHidden, decision.state.settleBias)
        assertEquals(FeedChromeTopRevealPolicy.DirectionOnly, decision.state.topRevealPolicy)
        assertEquals(0.5f, decision.nextFraction)
        assertEquals(-25f, decision.consumedY)
        assertEquals(FeedChromeSettleDelayMillis, decision.requestSettleAfterMillis)
    }

    @Test
    fun collapseLockedIgnoresReverseInput() {
        val state = FeedChromeBehaviorState(
            gesturePhase = FeedChromeGesturePhase.CollapseLocked,
            startVisibility = FeedChromeStartVisibility.VisibleOrPartial,
            settleBias = FeedChromeSettleBias.TowardHidden,
        )

        val decision = reduceFeedChromeUserScroll(
            state = state,
            delta = -25f,
            currentFraction = 0.5f,
            collapseDistancePx = 100,
        )

        assertEquals(state, decision.state)
        assertEquals(0.5f, decision.nextFraction)
        assertEquals(0f, decision.consumedY)
        assertNull(decision.requestSettleAfterMillis)
    }

    @Test
    fun revealAllowedExpandsChrome() {
        val decision = reduceFeedChromeUserScroll(
            state = FeedChromeBehaviorState(
                gesturePhase = FeedChromeGesturePhase.RevealAllowed,
                startVisibility = FeedChromeStartVisibility.FullyHidden,
            ),
            delta = -25f,
            currentFraction = 0.5f,
            collapseDistancePx = 100,
        )

        assertEquals(FeedChromeSettleBias.TowardVisible, decision.state.settleBias)
        assertEquals(0.25f, decision.nextFraction)
        assertEquals(25f, decision.consumedY)
        assertEquals(FeedChromeSettleDelayMillis, decision.requestSettleAfterMillis)
    }

    @Test
    fun boundaryInputChangesPhaseButDoesNotRequestSettle() {
        val decision = reduceFeedChromeUserScroll(
            state = FeedChromeBehaviorState(),
            delta = 10f,
            currentFraction = 1f,
            collapseDistancePx = 100,
        )

        assertEquals(FeedChromeGesturePhase.CollapseLocked, decision.state.gesturePhase)
        assertEquals(FeedChromeStartVisibility.FullyHidden, decision.state.startVisibility)
        assertEquals(FeedChromeSettleBias.Unknown, decision.state.settleBias)
        assertEquals(1f, decision.nextFraction)
        assertEquals(0f, decision.consumedY)
        assertNull(decision.requestSettleAfterMillis)
    }

    @Test
    fun postFlingAtTopForcesRevealWhenGestureStartedHidden() {
        val decision = reduceFeedChromePostFling(
            state = FeedChromeBehaviorState(
                gesturePhase = FeedChromeGesturePhase.RevealAllowed,
                startVisibility = FeedChromeStartVisibility.FullyHidden,
                settleBias = FeedChromeSettleBias.TowardHidden,
            ),
            currentFraction = 1f,
            isAtTop = true,
        )

        assertEquals(FeedChromeGesturePhase.Idle, decision.state.gesturePhase)
        assertEquals(FeedChromeTopRevealPolicy.ForceVisibleAtTop, decision.state.topRevealPolicy)
        assertEquals(0f, decision.targetFraction)
        assertEquals(0L, decision.delayMillis)
    }

    @Test
    fun postFlingAtTopKeepsDirectionWhenGestureStartedVisible() {
        val decision = reduceFeedChromePostFling(
            state = FeedChromeBehaviorState(
                gesturePhase = FeedChromeGesturePhase.CollapseLocked,
                startVisibility = FeedChromeStartVisibility.VisibleOrPartial,
                settleBias = FeedChromeSettleBias.TowardHidden,
            ),
            currentFraction = 0.5f,
            isAtTop = true,
        )

        assertEquals(FeedChromeTopRevealPolicy.DirectionOnly, decision.state.topRevealPolicy)
        assertEquals(1f, decision.targetFraction)
    }

    @Test
    fun postFlingDoesNotRequestSettleWhenAlreadyAtTarget() {
        val decision = reduceFeedChromePostFling(
            state = FeedChromeBehaviorState(
                startVisibility = FeedChromeStartVisibility.VisibleOrPartial,
                settleBias = FeedChromeSettleBias.TowardVisible,
            ),
            currentFraction = 0f,
            isAtTop = false,
        )

        assertNull(decision.targetFraction)
    }

    @Test
    fun atTopChangeRevealsOnlyWhenIdleAndFullyHidden() {
        val idleHidden = reduceFeedChromeAtTopChanged(
            state = FeedChromeBehaviorState(),
            currentFraction = 1f,
            isAtTop = true,
        )
        val activeGesture = reduceFeedChromeAtTopChanged(
            state = FeedChromeBehaviorState(gesturePhase = FeedChromeGesturePhase.RevealAllowed),
            currentFraction = 1f,
            isAtTop = true,
        )
        val partiallyVisible = reduceFeedChromeAtTopChanged(
            state = FeedChromeBehaviorState(),
            currentFraction = 0.5f,
            isAtTop = true,
        )
        val awayFromTop = reduceFeedChromeAtTopChanged(
            state = FeedChromeBehaviorState(),
            currentFraction = 1f,
            isAtTop = false,
        )

        assertEquals(0f, idleHidden.targetFraction)
        assertEquals(FeedChromeTopRevealPolicy.ForceVisibleAtTop, idleHidden.state.topRevealPolicy)
        assertNull(activeGesture.targetFraction)
        assertNull(partiallyVisible.targetFraction)
        assertNull(awayFromTop.targetFraction)
    }

    @Test
    fun contextChangeEndsGestureAndPreservesSettleInputs() {
        val state = FeedChromeBehaviorState(
            gesturePhase = FeedChromeGesturePhase.CollapseLocked,
            startVisibility = FeedChromeStartVisibility.FullyHidden,
            settleBias = FeedChromeSettleBias.TowardVisible,
            topRevealPolicy = FeedChromeTopRevealPolicy.ForceVisibleAtTop,
        )

        val nextState = reduceFeedChromeContextChanged(state)

        assertEquals(FeedChromeGesturePhase.Idle, nextState.gesturePhase)
        assertEquals(state.startVisibility, nextState.startVisibility)
        assertEquals(state.settleBias, nextState.settleBias)
        assertEquals(state.topRevealPolicy, nextState.topRevealPolicy)
    }

    @Test
    fun settleTargetUsesTopPolicyBeforeDirectionBias() {
        assertEquals(
            0f,
            feedChromeSettleTarget(
                state = FeedChromeBehaviorState(
                    settleBias = FeedChromeSettleBias.TowardHidden,
                    topRevealPolicy = FeedChromeTopRevealPolicy.ForceVisibleAtTop,
                ),
                isAtTop = true,
            ),
        )
        assertEquals(
            1f,
            feedChromeSettleTarget(
                state = FeedChromeBehaviorState(settleBias = FeedChromeSettleBias.TowardHidden),
                isAtTop = false,
            ),
        )
        assertEquals(
            0f,
            feedChromeSettleTarget(
                state = FeedChromeBehaviorState(settleBias = FeedChromeSettleBias.TowardVisible),
                isAtTop = false,
            ),
        )
    }
}
