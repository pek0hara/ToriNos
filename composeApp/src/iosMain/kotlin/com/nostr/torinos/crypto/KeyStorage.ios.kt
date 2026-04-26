package com.nostr.torinos.crypto

import platform.Foundation.NSUserDefaults

actual object KeyStorage {
    private const val KEY_PRIVATE = "torinos_private_key"
    private val defaults = NSUserDefaults.standardUserDefaults

    actual suspend fun savePrivateKey(hexKey: String) {
        val normalized = normalizePrivateKey(hexKey)
        val bytes = normalized.fromHex()
        derivePublicKey(bytes)

        defaults.setObject(normalized, KEY_PRIVATE)
        defaults.synchronize()
    }

    actual suspend fun loadPrivateKey(): String? {
        val raw = defaults.stringForKey(KEY_PRIVATE) ?: return null
        return runCatching {
            val normalized = normalizePrivateKey(raw)
            val bytes = normalized.fromHex()
            derivePublicKey(bytes)
            if (normalized != raw) {
                savePrivateKey(normalized)
            }
            normalized
        }.getOrElse {
            defaults.removeObjectForKey(KEY_PRIVATE)
            defaults.synchronize()
            null
        }
    }

    actual suspend fun hasKey(): Boolean =
        loadPrivateKey() != null

    actual suspend fun deleteKey() {
        defaults.removeObjectForKey(KEY_PRIVATE)
        defaults.synchronize()
    }
}
