package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.DisposableEffect

@Composable
fun rememberDismissKeyboard(): () -> Unit {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    return remember(focusManager, keyboardController) {
        {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            dismissPlatformKeyboard()
        }
    }
}

/**
 * 呼び出し元がコンポジションから外れる時(画面遷移、シート・ダイアログを閉じた時)にフォーカスと
 * キーボードを解除する。
 *
 * フォーカスを持ったままテキスト入力が破棄されると、iOSではテキスト入力セッションが終わらず、
 * 戻った先の画面のスクロールが重くなる(検索画面で実測、`docs/search-performance-fix-plan.md`)。
 * テキスト入力を持つ画面・シート・ダイアログは、その入力欄と同じComposableでこれを呼ぶ。
 * アプリのバックグラウンド遷移ではフォーカスを外さない。
 */
@Composable
fun DismissKeyboardOnLeave() {
    val dismissKeyboard = rememberDismissKeyboard()
    DisposableEffect(dismissKeyboard) {
        onDispose { dismissKeyboard() }
    }
}

internal expect fun dismissPlatformKeyboard()
