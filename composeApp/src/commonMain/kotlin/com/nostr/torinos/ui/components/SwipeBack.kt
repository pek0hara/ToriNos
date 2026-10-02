package com.nostr.torinos.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange

/** 右方向へのスワイプで [onBack] を呼ぶ。システムの戻る操作が届かない画面内遷移で使う。 */
fun Modifier.swipeBack(onBack: () -> Unit): Modifier = pointerInput(onBack) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var dragAmount = 0f
        val dragStart = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
            if (overSlop > 0f) {
                dragAmount = overSlop
                change.consume()
            }
        }
        if (dragStart != null) {
            val completed = horizontalDrag(dragStart.id) { change ->
                dragAmount += change.positionChange().x
                change.consume()
            }
            if (completed && dragAmount > SwipeBackThresholdPx) onBack()
        }
    }
}

private const val SwipeBackThresholdPx = 80f
