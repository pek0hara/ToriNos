package com.nostr.torinos.crypto

import androidx.compose.runtime.Composable
import com.nostr.torinos.crypto.interop.KeychainSaveInternetPassword
import com.nostr.torinos.util.logException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

private const val PASSWORDS_APP_SERVER = "nostr.torinos.app"

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberPasswordManagerSaver(): suspend (nsec: String, npub: String) -> Unit {
    return { nsec, npub ->
        runCatching {
            val bytes = nsec.encodeToByteArray()
            bytes.usePinned { pinned ->
                val data = NSData.create(
                    bytes = pinned.addressOf(0),
                    length = bytes.size.toULong(),
                )
                val status = KeychainSaveInternetPassword(PASSWORDS_APP_SERVER, npub, data)
                check(status == 0) { "Keychain internet password save failed (OSStatus=$status)" }
            }
        }.onFailure { e ->
            logException("PasswordManagerSaver", e, "Failed to save to iOS Passwords app")
        }
        Unit
    }
}
