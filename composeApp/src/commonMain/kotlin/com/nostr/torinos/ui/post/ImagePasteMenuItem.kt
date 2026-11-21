package com.nostr.torinos.ui.post

import androidx.compose.ui.Modifier

/**
 * 入力欄の長押しメニューに「画像をペースト」を足す。クリップボードに画像があるときだけ出す。
 * iOS だけが対象で、Android と Web では何もしない。
 */
internal expect fun Modifier.imagePasteMenuItem(
    enabled: Boolean,
    onPasteImage: () -> Unit,
): Modifier
