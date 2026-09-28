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

/**
 * 隠す方向へ動いたあと、同じセッション内の先頭方向入力でクロームを戻さない（ラッチ）。
 * 指を離す瞬間の逆向きの揺れで表示へ寄るのを防ぐためのもの。
 * 実機で必要かを確かめるため一時的に無効化している。戻すときは true にする。
 */
internal const val FeedChromeCollapseLatchEnabled = false

internal data class FeedChromeBehaviorState(
    val gesturePhase: FeedChromeGesturePhase = FeedChromeGesturePhase.Idle,
    val startVisibility: FeedChromeStartVisibility = FeedChromeStartVisibility.Unknown,
    val settleBias: FeedChromeSettleBias = FeedChromeSettleBias.Unknown,
    val topRevealPolicy: FeedChromeTopRevealPolicy = FeedChromeTopRevealPolicy.DirectionOnly,
)

internal data class FeedChromeScrollDecision(
    val state: FeedChromeBehaviorState,
    val nextFraction: Float,
)

internal data class FeedChromeSettleDecision(
    val state: FeedChromeBehaviorState,
    val targetFraction: Float?,
)

/**
 * クロームはリストに重ねて描くため、スクロール量は消費しない。
 * [delta] はリストが実際に動いた量で、クロームも同じ量だけ動かす。
 * [maxFraction] は先頭からのスクロール量で決まる上限で、先頭付近でクロームの下に空白を出さない。
 */
internal fun reduceFeedChromeUserScroll(
    state: FeedChromeBehaviorState,
    delta: Float,
    currentFraction: Float,
    collapseDistancePx: Int,
    maxFraction: Float = 1f,
    collapseLatchEnabled: Boolean = FeedChromeCollapseLatchEnabled,
): FeedChromeScrollDecision {
    if (delta == 0f) {
        return FeedChromeScrollDecision(
            state = state,
            nextFraction = currentFraction,
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

    val upperBound = maxFraction.coerceIn(0f, 1f)
    if (delta > 0f) {
        nextState = nextState.copy(gesturePhase = FeedChromeGesturePhase.CollapseLocked)
    } else if (collapseLatchEnabled && nextState.gesturePhase == FeedChromeGesturePhase.CollapseLocked) {
        // 隠す意図を保ったまま、上限だけは守る。先頭へ戻るとクロームはリストに押し下げられる。
        return FeedChromeScrollDecision(
            state = nextState,
            nextFraction = currentFraction.coerceAtMost(upperBound),
        )
    }

    val nextFraction = (currentFraction + delta / collapseDistancePx.toFloat())
        .coerceIn(0f, upperBound)
    val change = nextFraction - currentFraction
    if (change == 0f) {
        return FeedChromeScrollDecision(
            state = nextState,
            nextFraction = nextFraction,
        )
    }

    nextState = nextState.copy(
        settleBias = if (change > 0f) {
            FeedChromeSettleBias.TowardHidden
        } else {
            FeedChromeSettleBias.TowardVisible
        },
        topRevealPolicy = FeedChromeTopRevealPolicy.DirectionOnly,
    )
    return FeedChromeScrollDecision(
        state = nextState,
        nextFraction = nextFraction,
    )
}

/**
 * 指を離したあとの慣性スクロール（[delta]はリストが実際に動いた量）。
 * ドラッグと同じセッションの続きとして扱い、クロームも内容と同じ量だけ動かす。
 * 慣性中にクロームが途中の透明度で止まり、内容の上に薄く残るのを防ぐ。
 * セッション外（[FeedChromeGesturePhase.Idle]）の慣性では、開始時の表示状態を記録し直さないよう何もしない。
 */
internal fun reduceFeedChromeFlingScroll(
    state: FeedChromeBehaviorState,
    delta: Float,
    currentFraction: Float,
    collapseDistancePx: Int,
    maxFraction: Float = 1f,
    collapseLatchEnabled: Boolean = FeedChromeCollapseLatchEnabled,
): FeedChromeScrollDecision {
    if (state.gesturePhase == FeedChromeGesturePhase.Idle) {
        return FeedChromeScrollDecision(
            state = state,
            nextFraction = currentFraction,
        )
    }
    return reduceFeedChromeUserScroll(
        state = state,
        delta = delta,
        currentFraction = currentFraction,
        collapseDistancePx = collapseDistancePx,
        maxFraction = maxFraction,
        collapseLatchEnabled = collapseLatchEnabled,
    )
}

internal fun reduceFeedChromePostFling(
    state: FeedChromeBehaviorState,
    currentFraction: Float,
    isAtTop: Boolean,
    maxFraction: Float = 1f,
): FeedChromeSettleDecision {
    // 縦スクロールが一度も起きていないフリング（タブの横スワイプなど）では寄せない。
    if (state.gesturePhase == FeedChromeGesturePhase.Idle) {
        return FeedChromeSettleDecision(
            state = state,
            targetFraction = null,
        )
    }
    val nextState = state.copy(
        gesturePhase = FeedChromeGesturePhase.Idle,
        topRevealPolicy = if (state.startVisibility == FeedChromeStartVisibility.FullyHidden) {
            FeedChromeTopRevealPolicy.ForceVisibleAtTop
        } else {
            FeedChromeTopRevealPolicy.DirectionOnly
        },
    )
    val targetFraction = feedChromeSettleTarget(nextState, isAtTop)
        .coerceAtMost(maxFraction.coerceIn(0f, 1f))
    return FeedChromeSettleDecision(
        state = nextState,
        targetFraction = targetFraction.takeIf { it != currentFraction },
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
        )
    }

    return FeedChromeSettleDecision(
        state = state.copy(topRevealPolicy = FeedChromeTopRevealPolicy.ForceVisibleAtTop),
        targetFraction = 0f,
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

/**
 * 先頭からのスクロール量 [scrolledFromTopPx] に対する折りたたみ量の上限。
 * クロームの高さ以上スクロールしていれば完全に隠せる。null は先頭の項目が見えていない(十分離れている)。
 */
internal fun feedChromeMaxFraction(scrolledFromTopPx: Int?, collapseDistancePx: Int): Float =
    when {
        collapseDistancePx <= 0 || scrolledFromTopPx == null -> 1f
        else -> (scrolledFromTopPx.toFloat() / collapseDistancePx).coerceIn(0f, 1f)
    }
