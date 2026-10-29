package com.nostr.torinos.ui.components

import androidx.compose.ui.window.DialogProperties

/**
 * 画面全体を覆う Dialog（投稿シート・プロフィール編集シート）の設定。
 * iOS・Web は既定だと中身を安全領域の内側に収めるため、上下に下の画面がのぞく。
 * 全面に広げ、安全領域は中身の側で `WindowInsets.safeDrawing` を使って避ける。
 */
expect fun fullScreenDialogProperties(
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = false,
): DialogProperties
