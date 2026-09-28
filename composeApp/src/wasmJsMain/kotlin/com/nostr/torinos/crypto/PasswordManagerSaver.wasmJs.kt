package com.nostr.torinos.crypto

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPasswordManagerSaver(): suspend (nsec: String, npub: String) -> Unit {
    // ブラウザのパスワードマネージャー連携は行わない。鍵生成画面の nsec 表示とコピーで保管してもらう。
    return { _, _ -> }
}
