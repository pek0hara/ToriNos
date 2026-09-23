package com.nostr.torinos.ui.feed

internal enum class FeedChromeGesturePhase {
    Idle,
    RevealAllowed,
    CollapseLocked,
}

internal enum class FeedChromeStartVisibility {
    Unknown,
    FullyHidden,
    VisibleOrPartial,
}

internal enum class FeedChromeSettleBias {
    Unknown,
    TowardVisible,
    TowardHidden,
}

internal enum class FeedChromeTopRevealPolicy {
    DirectionOnly,
    ForceVisibleAtTop,
}

internal data class FeedChromeBehaviorState(
    val gesturePhase: FeedChromeGesturePhase = FeedChromeGesturePhase.Idle,
    val startVisibility: FeedChromeStartVisibility = FeedChromeStartVisibility.Unknown,
    val settleBias: FeedChromeSettleBias = FeedChromeSettleBias.Unknown,
    val topRevealPolicy: FeedChromeTopRevealPolicy = FeedChromeTopRevealPolicy.DirectionOnly,
)

internal data class FeedChromeScrollDecision(
    val state: FeedChromeBehaviorState,
    val nextFraction: Float,
    val consumedY: Float,
    val requestSettleAfterMillis: Long?,
)

internal data class FeedChromeSettleDecision(
    val state: FeedChromeBehaviorState,
    val targetFraction: Float?,
    val delayMillis: Long,
)

internal fun reduceFeedChromeUserScroll(
    state: FeedChromeBehaviorState,
    delta: Float,
    currentFraction: Float,
    collapseDistancePx: Int,
): FeedChromeScrollDecision {
    if (delta == 0f) {
        return FeedChromeScrollDecision(
            state = state,
            nextFraction = currentFraction,
            consumedY = 0f,
            requestSettleAfterMillis = null,
        )
    }

    var nextState = if (state.gesturePhase == FeedChromeGesturePhase.Idle) {
        state.copy(
            gesturePhase = FeedChromeGesturePhase.RevealAllowed,
            startVisibility = if (currentFraction >= 1f) {
                FeedChromeStartVisibility.FullyHidden
            } else {
                FeedChromeStartVisibility.VisibleOrPartial
            },
        )
    } else {
        state
    }

    if (delta > 0f) {
        nextState = nextState.copy(gesturePhase = FeedChromeGesturePhase.CollapseLocked)
    } else if (nextState.gesturePhase == FeedChromeGesturePhase.CollapseLocked) {
        return FeedChromeScrollDecision(
            state = nextState,
            nextFraction = currentFraction,
            consumedY = 0f,
            requestSettleAfterMillis = null,
        )
    }

    val nextFraction = (currentFraction + delta / collapseDistancePx.toFloat())
        .coerceIn(0f, 1f)
    val consumedDelta = (nextFraction - currentFraction) * collapseDistancePx
    if (consumedDelta == 0f) {
        return FeedChromeScrollDecision(
            state = nextState,
            nextFraction = nextFraction,
            consumedY = 0f,
            requestSettleAfterMillis = null,
        )
    }

    nextState = nextState.copy(
        settleBias = if (consumedDelta > 0f) {
            FeedChromeSettleBias.TowardHidden
        } else {
            FeedChromeSettleBias.TowardVisible
        },
        topRevealPolicy = FeedChromeTopRevealPolicy.DirectionOnly,
    )
    return FeedChromeScrollDecision(
        state = nextState,
        nextFraction = nextFraction,
        consumedY = -consumedDelta,
        requestSettleAfterMillis = FeedChromeSettleDelayMillis,
    )
}

internal fun reduceFeedChromePostFling(
    state: FeedChromeBehaviorState,
    currentFraction: Float,
    isAtTop: Boolean,
): FeedChromeSettleDecision {
    val nextState = state.copy(
        gesturePhase = FeedChromeGesturePhase.Idle,
        topRevealPolicy = if (state.startVisibility == FeedChromeStartVisibility.FullyHidden) {
            FeedChromeTopRevealPolicy.ForceVisibleAtTop
        } else {
            FeedChromeTopRevealPolicy.DirectionOnly
        },
    )
    val targetFraction = feedChromeSettleTarget(nextState, isAtTop)
    return FeedChromeSettleDecision(
        state = nextState,
        targetFraction = targetFraction.takeIf { it != currentFraction },
        delayMillis = 0L,
    )
}

internal fun reduceFeedChromeAtTopChanged(
    state: FeedChromeBehaviorState,
    currentFraction: Float,
    isAtTop: Boolean,
): FeedChromeSettleDecision {
    if (
        !isAtTop ||
        state.gesturePhase != FeedChromeGesturePhase.Idle ||
        currentFraction < 1f
    ) {
        return FeedChromeSettleDecision(
            state = state,
            targetFraction = null,
            delayMillis = 0L,
        )
    }

    return FeedChromeSettleDecision(
        state = state.copy(topRevealPolicy = FeedChromeTopRevealPolicy.ForceVisibleAtTop),
        targetFraction = 0f,
        delayMillis = 0L,
    )
}

internal fun reduceFeedChromeContextChanged(
    state: FeedChromeBehaviorState,
): FeedChromeBehaviorState = state.copy(gesturePhase = FeedChromeGesturePhase.Idle)

internal fun feedChromeSettleTarget(
    state: FeedChromeBehaviorState,
    isAtTop: Boolean,
): Float = when {
    isAtTop && state.topRevealPolicy == FeedChromeTopRevealPolicy.ForceVisibleAtTop -> 0f
    state.settleBias == FeedChromeSettleBias.TowardVisible -> 0f
    else -> 1f
}

internal const val FeedChromeSettleDelayMillis = 60L
