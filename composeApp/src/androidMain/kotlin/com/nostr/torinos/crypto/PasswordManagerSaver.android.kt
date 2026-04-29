package com.nostr.torinos.crypto

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager

@Composable
actual fun rememberPasswordManagerSaver(): suspend (nsec: String, npub: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { nsec, npub ->
            runCatching {
                CredentialManager.create(context).createCredential(
                    context = context,
                    request = CreatePasswordRequest(id = npub, password = nsec),
                )
            }
            Unit
        }
    }
}
