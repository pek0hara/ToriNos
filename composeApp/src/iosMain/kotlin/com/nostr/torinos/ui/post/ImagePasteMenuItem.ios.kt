package com.nostr.torinos.ui.post

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.ui.Modifier
import platform.UIKit.UIPasteboard

private data object ImagePasteMenuKey

/**
 * Compose の新しいテキストメニュー(`ComposeFoundationFlags.isNewContextMenuEnabled`)が有効なとき、
 * 足した項目は iOS 標準の編集メニューに並ぶ。`hasImages` は中身を読まないので、許可ダイアログは出ない。
 */
@OptIn(ExperimentalFoundationApi::class)
internal actual fun Modifier.imagePasteMenuItem(
    enabled: Boolean,
    onPasteImage: () -> Unit,
): Modifier = if (!enabled) this else appendTextContextMenuComponents {
    if (UIPasteboard.generalPasteboard.hasImages) {
        item(key = ImagePasteMenuKey, label = "画像をペースト") {
            close()
            onPasteImage()
        }
    }
}
