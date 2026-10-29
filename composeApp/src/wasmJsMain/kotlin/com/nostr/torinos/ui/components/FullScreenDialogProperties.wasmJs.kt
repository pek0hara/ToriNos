package com.nostr.torinos.ui.components

import androidx.compose.ui.window.DialogProperties

actual fun fullScreenDialogProperties(
    dismissOnBackPress: Boolean,
    dismissOnClickOutside: Boolean,
): DialogProperties = DialogProperties(
    dismissOnBackPress = dismissOnBackPress,
    dismissOnClickOutside = dismissOnClickOutside,
    usePlatformDefaultWidth = false,
    // 安全領域では縮めない（上下のすき間をなくす）。キーボードの分は今までどおり縮める。
    usePlatformInsets = false,
)
