package com.nostr.torinos.crypto

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPasswordManagerSaver(): suspend (nsec: String, npub: String) -> Unit {
    return { _, _ -> }
}
