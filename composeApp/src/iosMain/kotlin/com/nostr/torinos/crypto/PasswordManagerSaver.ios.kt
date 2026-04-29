package com.nostr.torinos.crypto

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPasswordManagerSaver(): suspend (nsec: String, npub: String) -> Unit {
    // iCloud Keychain への保存は KeyStorage.savePrivateKey() 内で kSecAttrSynchronizable = true
    // として行われるため、ここでは何もしない。
    return { _, _ -> }
}
