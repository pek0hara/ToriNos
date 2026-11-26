package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.UIKitNativeTextInputContext
import androidx.compose.ui.uikit.LocalNativeTextInputContext
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIKeyboardDidShowNotification
import platform.UIKit.UIKeyInputProtocol
import platform.UIKit.UIKeyboardWillShowNotification
import platform.UIKit.UIPasteboard
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * キーボードの候補欄に出る「〜からペースト」を使えるようにする。
 * 文字はその欄に入れ、画像だけのときは画像を受け付ける欄([KeyboardImagePaste])に渡す。
 *
 * 候補欄のペーストは、キーボードの相手(Compose の隠れた入力ビュー)に `paste:` を送る。
 * Compose 1.12 の iOS では、`ComposeFoundationFlags.isNewContextMenuEnabled` を有効にすると
 * その入力ビューにペーストの処理が渡されなくなり(新しいメニューはネイティブ入力のときしか渡さない)、
 * `paste:` が無視される。長押しメニューは別のビューで出るので影響しない。
 *
 * 入力ビューは入力欄にフォーカスするたびに作り直されるので、キーボードが出るたびに渡し直す。
 * まだ入力が始まっていなければ、Compose が次に入力を始めたときに渡してくれる。
 */
@OptIn(InternalComposeUiApi::class)
@Composable
internal fun KeyboardPasteSupport() {
    val context = LocalNativeTextInputContext.current
    DisposableEffect(context) {
        context.provideKeyboardPaste()
        val center = NSNotificationCenter.defaultCenter
        // queue = null: 通知を出した(メイン)スレッドでその場で呼ぶ。入力の開始と順番が入れ替わらないように。
        val observers = listOf(UIKeyboardWillShowNotification, UIKeyboardDidShowNotification).map { name ->
            center.addObserverForName(name = name, `object` = null, queue = null) { _ ->
                context.provideKeyboardPaste()
            }
        }
        onDispose { observers.forEach(center::removeObserver) }
    }
}

@OptIn(InternalComposeUiApi::class)
private fun UIKitNativeTextInputContext.provideKeyboardPaste() {
    // ネイティブ入力ではメニューの状態を Compose 自身が渡すので、上書きしない。
    if (usingNativeTextInput()) return
    updateNativeTextInputEditMenuState(
        copy = null,
        paste = ::pasteFromKeyboard,
        cut = null,
        selectAll = null,
        customActions = null,
    )
}

/**
 * `paste:` の中から呼ばれるので、ここでクリップボードを読んでも許可の確認は出ない。
 * 入力中の欄(ファーストレスポンダ)に insertText で入れる。キーボードで打ったのと同じ経路で、選択範囲は置き換わる。
 * (宛先 nil の sendAction は、入力ビューの canPerformAction に断られて届かない。)
 */
private fun pasteFromKeyboard() {
    val pasteboard = UIPasteboard.generalPasteboard
    val text = pasteboard.string
    if (text.isNullOrEmpty()) {
        if (pasteboard.hasImages) KeyboardImagePaste.paste()
        return
    }
    val windows = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { it.windows.filterIsInstance<UIWindow>() }
    val responder = windows.firstNotNullOfOrNull { findFirstResponder(it) } as? UIKeyInputProtocol ?: return
    responder.insertText(text)
}

private fun findFirstResponder(view: UIView): UIView? {
    if (view.isFirstResponder()) return view
    return view.subviews.firstNotNullOfOrNull { findFirstResponder(it as UIView) }
}

/**
 * キーボードからの画像のペーストを受け取る欄。画像を貼れる欄([com.nostr.torinos.ui.post.imagePasteMenuItem])が
 * フォーカスを得たときに登録し、外れたら消す。今フォーカスしている欄だけが受け取る。
 */
internal object KeyboardImagePaste {
    class Target(val paste: () -> Unit)

    private var focused: Target? = null

    fun focus(target: Target) {
        focused = target
    }

    fun release(target: Target) {
        if (focused === target) focused = null
    }

    fun paste() {
        focused?.paste?.invoke()
    }
}
