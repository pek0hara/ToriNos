package com.nostr.torinos

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.nostr.torinos.ui.components.registerAppImageLoader

@OptIn(ExperimentalFoundationApi::class)
fun MainViewController() = ComposeUIViewController(
    configure = {
        onFocusBehavior = OnFocusBehavior.DoNothing
        // 入力欄の長押しメニューに独自の項目(画像をペースト)を足すため。iOS では既定で無効。
        ComposeFoundationFlags.isNewContextMenuEnabled = true
    },
) {
    registerAppImageLoader()
    registerMemoryWarningObserver()
    App()
}
