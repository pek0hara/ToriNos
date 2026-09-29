package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.DisposableEffect

@Composable
fun rememberDismissKeyboard(): () -> Unit {
    val focusManager = LocalFocusManager.current
    val hideKeyboard = rememberHideKeyboard()

    return remember(focusManager, hideKeyboard) {
        {
            focusManager.clearFocus(force = true)
            hideKeyboard()
        }
    }
}

/**
 * フォーカスは変えず、ソフトウェアキーボードだけを閉じる。
 *
 * `LocalSoftwareKeyboardController.hide()` だけではiOSの入力セッションが終わらないので、
 * [dismissPlatformKeyboard] も併せて呼ぶ。フォーカス喪失をきっかけに閉じる場合に使う。
 */
@Composable
fun rememberHideKeyboard(): () -> Unit {
    val keyboardController = LocalSoftwareKeyboardController.current

    return remember(keyboardController) {
        {
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

/**
 * ソフトウェアキーボードが表示中か。
 *
 * iOSはキーボード分だけ画面全体が縮むため `WindowInsets.ime` が0のままになる。
 * キーボード表示中にホームインジケーター分の余白を外す判定に使う。
 */
@Composable
internal expect fun rememberSoftwareKeyboardVisible(): Boolean
