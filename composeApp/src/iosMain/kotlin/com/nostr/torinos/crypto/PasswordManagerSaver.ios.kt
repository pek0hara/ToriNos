package com.nostr.torinos.crypto

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPasswordManagerSaver(): suspend (nsec: String, npub: String) -> Unit {
    // iOS ではアプリ内 Keychain への保存を KeyStorage.savePrivateKey() で行うため、ここでは何もしない。
    return { _, _ -> }
}
