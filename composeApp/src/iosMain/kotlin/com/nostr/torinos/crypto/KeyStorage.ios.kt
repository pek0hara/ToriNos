package com.nostr.torinos.crypto

import com.nostr.torinos.crypto.interop.KeychainDeleteData
import com.nostr.torinos.crypto.interop.KeychainLoadData
import com.nostr.torinos.crypto.interop.KeychainSaveData
import com.nostr.torinos.util.logException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.create

private const val KEYCHAIN_SERVICE = "com.nostr.torinos"
private const val KEYCHAIN_ACCOUNT = "private_key"
private const val LEGACY_DEFAULTS_KEY = "torinos_private_key"

@OptIn(ExperimentalForeignApi::class)
private fun loadPrivateKeyFromKeychain(synchronizable: Boolean? = null): String? {
    val nsData = KeychainLoadData(
        KEYCHAIN_SERVICE,
        KEYCHAIN_ACCOUNT,
        includeSynchronizable = synchronizable != null,
        synchronizable = synchronizable ?: false,
    ) ?: return null
    val bytePtr: CPointer<ByteVar> = nsData.bytes?.reinterpret()
        ?: return null
    val str = ByteArray(nsData.length.toInt()) { i -> bytePtr[i] }.decodeToString()

    return runCatching {
        val norm = normalizePrivateKey(str)
        derivePublicKey(norm.fromHex())
        norm
    }.getOrElse { e ->
        logException("KeyStorage", e, "Keychain data exists but is corrupt (synchronizable=$synchronizable); will be deleted")
        null
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object KeyStorage {

    actual suspend fun savePrivateKey(hexKey: String) {
        val normalized = normalizePrivateKey(hexKey)
        derivePublicKey(normalized.fromHex())

        val bytes = normalized.encodeToByteArray()
        bytes.usePinned { pinned ->
            val data = NSData.create(
                bytes = pinned.addressOf(0),
                length = bytes.size.toULong(),
            )
            val status = KeychainSaveData(
                KEYCHAIN_SERVICE,
                KEYCHAIN_ACCOUNT,
                data,
            )
            check(status == 0) { "Keychain add failed (OSStatus=$status)" }
        }
    }

    actual suspend fun loadPrivateKey(): String? {
        // Migrate from NSUserDefaults if a legacy key exists.
        val defaults = NSUserDefaults.standardUserDefaults
        val legacy = defaults.stringForKey(LEGACY_DEFAULTS_KEY)
        if (legacy != null) {
            savePrivateKey(legacy)
            defaults.removeObjectForKey(LEGACY_DEFAULTS_KEY)
            defaults.synchronize()
        }

        loadPrivateKeyFromKeychain()?.let { return it }

        val synchronizedKey = loadPrivateKeyFromKeychain(synchronizable = true)
        if (synchronizedKey != null) {
            savePrivateKey(synchronizedKey)
            return synchronizedKey
        }

        deleteKeychainEntries()
        return null
    }

    actual suspend fun hasKey(): Boolean = loadPrivateKey() != null

    actual suspend fun deleteKey() {
        deleteKeychainEntries()
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.removeObjectForKey(LEGACY_DEFAULTS_KEY)
        defaults.synchronize()
    }

    private fun deleteKeychainEntries() {
        KeychainDeleteData(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT, includeSynchronizable = false, synchronizable = false)
        KeychainDeleteData(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT, includeSynchronizable = true, synchronizable = true)
    }
}
