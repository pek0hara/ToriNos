package com.nostr.torinos.ui.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeedChromeBehaviorTest {
    @Test
    fun zeroDeltaDoesNotChangeState() {
        val state = FeedChromeBehaviorState()

        val decision = reduceFeedChromeUserScroll(
            state = state,
            delta = 0f,
            currentFraction = 0.4f,
            collapseDistancePx = 100,
        )

        assertEquals(state, decision.state)
        assertEquals(0.4f, decision.nextFraction)
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
    fun collapseInputUpdatesFractionAndSettleState() {
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
            collapseLatchEnabled = true,
        )

        assertEquals(state, decision.state)
        assertEquals(0.5f, decision.nextFraction)
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
    }

    @Test
    fun boundaryInputChangesPhaseButNotSettleBias() {
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
                gesturePhase = FeedChromeGesturePhase.RevealAllowed,
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
    fun collapseStopsAtScrolledDistanceNearTop() {
        val decision = reduceFeedChromeUserScroll(
            state = FeedChromeBehaviorState(),
            delta = 50f,
            currentFraction = 0f,
            collapseDistancePx = 100,
            maxFraction = 0.3f,
        )

        assertEquals(0.3f, decision.nextFraction)
        assertEquals(FeedChromeSettleBias.TowardHidden, decision.state.settleBias)
    }

    @Test
    fun collapseLockedStillFollowsListBackTowardTop() {
        val state = FeedChromeBehaviorState(
            gesturePhase = FeedChromeGesturePhase.CollapseLocked,
            startVisibility = FeedChromeStartVisibility.VisibleOrPartial,
            settleBias = FeedChromeSettleBias.TowardHidden,
        )

        val decision = reduceFeedChromeUserScroll(
            state = state,
            delta = -25f,
            currentFraction = 1f,
            collapseDistancePx = 100,
            collapseLatchEnabled = true,
            maxFraction = 0.4f,
        )

        assertEquals(state, decision.state)
        assertEquals(0.4f, decision.nextFraction)
    }

    @Test
    fun postFlingHiddenTargetStopsAtScrolledDistance() {
        val decision = reduceFeedChromePostFling(
            state = FeedChromeBehaviorState(
                gesturePhase = FeedChromeGesturePhase.CollapseLocked,
                startVisibility = FeedChromeStartVisibility.VisibleOrPartial,
                settleBias = FeedChromeSettleBias.TowardHidden,
            ),
            currentFraction = 0.2f,
            isAtTop = false,
            maxFraction = 0.6f,
        )

        assertEquals(0.6f, decision.targetFraction)
    }

    @Test
    fun maxFractionFollowsDistanceFromTop() {
        assertEquals(0f, feedChromeMaxFraction(scrolledFromTopPx = 0, collapseDistancePx = 200))
        assertEquals(0.5f, feedChromeMaxFraction(scrolledFromTopPx = 100, collapseDistancePx = 200))
        assertEquals(1f, feedChromeMaxFraction(scrolledFromTopPx = 500, collapseDistancePx = 200))
        assertEquals(1f, feedChromeMaxFraction(scrolledFromTopPx = null, collapseDistancePx = 200))
        assertEquals(1f, feedChromeMaxFraction(scrolledFromTopPx = 0, collapseDistancePx = 0))
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
    @Test
    fun flingContinuesCollapseWithContent() {
        val decision = reduceFeedChromeFlingScroll(
            state = FeedChromeBehaviorState(
                gesturePhase = FeedChromeGesturePhase.CollapseLocked,
                startVisibility = FeedChromeStartVisibility.VisibleOrPartial,
                settleBias = FeedChromeSettleBias.TowardHidden,
            ),
            delta = 40f,
            currentFraction = 0.2f,
            collapseDistancePx = 100,
        )

        assertEquals(0.6f, decision.nextFraction, 0.0001f)
        assertEquals(FeedChromeGesturePhase.CollapseLocked, decision.state.gesturePhase)
    }

    @Test
    fun flingPastCollapseDistanceFullyHidesChrome() {
        val decision = reduceFeedChromeFlingScroll(
            state = FeedChromeBehaviorState(
                gesturePhase = FeedChromeGesturePhase.CollapseLocked,
                startVisibility = FeedChromeStartVisibility.VisibleOrPartial,
            ),
            delta = 500f,
            currentFraction = 0.2f,
            collapseDistancePx = 100,
        )

        assertEquals(1f, decision.nextFraction)
    }

    @Test
    fun flingKeepsCollapseLatch() {
        val state = FeedChromeBehaviorState(
            gesturePhase = FeedChromeGesturePhase.CollapseLocked,
            startVisibility = FeedChromeStartVisibility.VisibleOrPartial,
            settleBias = FeedChromeSettleBias.TowardHidden,
        )

        val decision = reduceFeedChromeFlingScroll(
            state = state,
            delta = -30f,
            currentFraction = 0.5f,
            collapseDistancePx = 100,
            collapseLatchEnabled = true,
        )

        assertEquals(state, decision.state)
        assertEquals(0.5f, decision.nextFraction)
    }

    @Test
    fun flingInRevealSessionExpandsChrome() {
        val decision = reduceFeedChromeFlingScroll(
            state = FeedChromeBehaviorState(
                gesturePhase = FeedChromeGesturePhase.RevealAllowed,
                startVisibility = FeedChromeStartVisibility.FullyHidden,
            ),
            delta = -30f,
            currentFraction = 0.8f,
            collapseDistancePx = 100,
        )

        assertEquals(0.5f, decision.nextFraction, 0.0001f)
        assertEquals(FeedChromeSettleBias.TowardVisible, decision.state.settleBias)
    }

    @Test
    fun flingRespectsMaxFraction() {
        val decision = reduceFeedChromeFlingScroll(
            state = FeedChromeBehaviorState(gesturePhase = FeedChromeGesturePhase.CollapseLocked),
            delta = 50f,
            currentFraction = 0.1f,
            collapseDistancePx = 100,
            maxFraction = 0.3f,
        )

        assertEquals(0.3f, decision.nextFraction)
    }

    @Test
    fun flingOutsideSessionDoesNothing() {
        val state = FeedChromeBehaviorState()

        val decision = reduceFeedChromeFlingScroll(
            state = state,
            delta = 50f,
            currentFraction = 0.4f,
            collapseDistancePx = 100,
        )

        assertEquals(state, decision.state)
        assertEquals(0.4f, decision.nextFraction)
    }
    @Test
    fun postFlingWithoutVerticalScrollDoesNotSettle() {
        // タブの横スワイプなど、縦スクロールが起きていないフリング。前回の方向が残っていても寄せない。
        val state = FeedChromeBehaviorState(
            gesturePhase = FeedChromeGesturePhase.Idle,
            startVisibility = FeedChromeStartVisibility.VisibleOrPartial,
            settleBias = FeedChromeSettleBias.TowardHidden,
        )

        val decision = reduceFeedChromePostFling(
            state = state,
            currentFraction = 0f,
            isAtTop = false,
        )

        assertEquals(state, decision.state)
        assertNull(decision.targetFraction)
    }
    @Test
    fun reverseInputRevealsWhenCollapseLatchDisabled() {
        val decision = reduceFeedChromeUserScroll(
            state = FeedChromeBehaviorState(
                gesturePhase = FeedChromeGesturePhase.CollapseLocked,
                startVisibility = FeedChromeStartVisibility.VisibleOrPartial,
                settleBias = FeedChromeSettleBias.TowardHidden,
            ),
            delta = -25f,
            currentFraction = 0.5f,
            collapseDistancePx = 100,
            collapseLatchEnabled = false,
        )

        assertEquals(0.25f, decision.nextFraction)
        assertEquals(FeedChromeSettleBias.TowardVisible, decision.state.settleBias)
    }
}
