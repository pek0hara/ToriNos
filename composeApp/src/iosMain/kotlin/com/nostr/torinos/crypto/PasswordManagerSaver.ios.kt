package com.nostr.torinos.crypto

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPasswordManagerSaver(): suspend (nsec: String, npub: String) -> Unit {
    // iOS のアカウント同期は KeyStorage のアプリ専用 iCloud Keychain に一本化する。
    return { _, _ -> }
}
