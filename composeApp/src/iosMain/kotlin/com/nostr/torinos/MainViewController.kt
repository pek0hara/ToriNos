package com.nostr.torinos

import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.nostr.torinos.ui.components.registerAppImageLoader

fun MainViewController() = ComposeUIViewController(
    configure = {
        onFocusBehavior = OnFocusBehavior.DoNothing
    },
) {
    registerAppImageLoader()
    registerMemoryWarningObserver()
    App()
}
