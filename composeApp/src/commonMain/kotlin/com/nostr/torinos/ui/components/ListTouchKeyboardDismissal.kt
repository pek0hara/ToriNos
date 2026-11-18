package com.nostr.torinos.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

/**
 * 一覧のスクロールでキーボードを閉じるかを決める。
 *
 * 閉じるのは指で一覧を動かしたときだけで、新着での自動スクロールや慣性スクロールでは閉じない。
 * 1回の操作で閉じるのは1度だけにし、指を離したら([onDragEnd])次の操作でまた閉じられるようにする。
 */
internal class ListTouchKeyboardDismissal {
    private var dismissedThisDrag = false

    /** スクロールが起きたとき。閉じるべきなら true。 */
    fun onScroll(fromUser: Boolean, delta: Float): Boolean {
        if (!fromUser || delta == 0f || dismissedThisDrag) return false
        dismissedThisDrag = true
        return true
    }

    fun onDragEnd() {
        dismissedThisDrag = false
    }
}

/**
 * 下端に入力欄があるチャット系の一覧に付け、一覧を触ったらキーボードを閉じる。
 *
 * - 指で一覧をスクロールし始めたら閉じる(向きは問わない)。
 * - 一覧の空いているところをタップしたら閉じる。メッセージの長押しやリンクなど、
 *   子がタップを受け取った場合は何もしない。
 *
 * 入力中の本文や返信先はそのまま残す。iOSで入力が終わらずスクロールが重くなるのを避けるため、
 * フォーカスごと外す [rememberDismissKeyboard] で閉じる。
 * スクロールする一覧(LazyColumn など)の modifier に付けること。
 */
@Composable
fun Modifier.dismissKeyboardOnListTouch(): Modifier {
    val dismissKeyboard by rememberUpdatedState(rememberDismissKeyboard())
    val connection = remember {
        val dismissal = ListTouchKeyboardDismissal()
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (dismissal.onScroll(fromUser = source == NestedScrollSource.UserInput, delta = available.y)) {
                    dismissKeyboard()
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // 指を離すと、速度が0でも必ずフリングが呼ばれる。
                dismissal.onDragEnd()
                return Velocity.Zero
            }
        }
    }
    return this
        .nestedScroll(connection)
        .pointerInput(Unit) {
            awaitEachGesture {
                // 子(メッセージのタップ・長押し、リンク)が先に受け取った押下は見ない。
                awaitFirstDown()
                // スクロールに変わった場合や子が離しを受け取った場合は null になる。
                if (waitForUpOrCancellation() != null) dismissKeyboard()
            }
        }
}
