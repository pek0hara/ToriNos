package com.nostr.torinos.ui.post

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import com.nostr.torinos.ui.components.KeyboardImagePaste
import platform.UIKit.UIPasteboard

private data object ImagePasteMenuKey

/**
 * Compose の新しいテキストメニュー(`ComposeFoundationFlags.isNewContextMenuEnabled`)が有効なとき、
 * 足した項目は iOS 標準の編集メニューに並ぶ。`hasImages` は中身を読まないので、許可ダイアログは出ない。
 *
 * フォーカス中はキーボード候補欄からの画像のペーストも受け取る(`KeyboardPasteSupport.ios.kt`)。
 * `paste:` の中で同期的に読むので、こちらも許可ダイアログは出ない。
 */
@OptIn(ExperimentalFoundationApi::class)
internal actual fun Modifier.imagePasteMenuItem(
    enabled: Boolean,
    onPasteImage: () -> Unit,
): Modifier = composed {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnPasteImage by rememberUpdatedState(onPasteImage)
    val keyboardTarget = remember {
        KeyboardImagePaste.Target { if (currentEnabled) currentOnPasteImage() }
    }
    DisposableEffect(keyboardTarget) {
        onDispose { KeyboardImagePaste.release(keyboardTarget) }
    }
    val focusTracking = Modifier.onFocusChanged {
        if (it.isFocused) KeyboardImagePaste.focus(keyboardTarget) else KeyboardImagePaste.release(keyboardTarget)
    }
    if (!enabled) focusTracking else focusTracking.appendTextContextMenuComponents {
        if (UIPasteboard.generalPasteboard.hasImages) {
            item(key = ImagePasteMenuKey, label = "画像をペースト") {
                close()
                onPasteImage()
            }
        }
    }
}
