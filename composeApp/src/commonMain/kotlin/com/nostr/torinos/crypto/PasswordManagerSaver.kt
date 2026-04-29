package com.nostr.torinos.crypto

import androidx.compose.runtime.Composable

/**
 * 秘密鍵を OS のパスワードマネージャーに保存する関数を返す。
 * iOS: iCloud Keychain への保存は KeyStorage.savePrivateKey() 内で完結するため no-op。
 * Android: Google パスワードマネージャーに保存するダイアログを表示。
 */
@Composable
expect fun rememberPasswordManagerSaver(): suspend (nsec: String, npub: String) -> Unit
