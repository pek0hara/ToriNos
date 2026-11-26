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
    // 上のフラグで使えなくなるキーボード候補欄のペーストを補う。
    com.nostr.torinos.ui.components.KeyboardPasteSupport()
    App()
}

/**
 * OS から渡された URL（`torinos://join?…`）を受け取る。招待なら預け、ログインの後に参加シートで確かめる
 * （docs/buzz-invite-link-design.md 5.2）。URL には招待コードが入るのでログに出さない。
 */
fun handleOpenUrl(url: String) {
    com.nostr.torinos.chat.pendingInvites.offerAppLink(url, kotlin.time.Clock.System.now().toEpochMilliseconds())
}
