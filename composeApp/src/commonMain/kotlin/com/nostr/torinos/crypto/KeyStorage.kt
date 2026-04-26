package com.nostr.torinos.crypto

expect object KeyStorage {
    suspend fun savePrivateKey(hexKey: String)
    suspend fun loadPrivateKey(): String?
    suspend fun hasKey(): Boolean
    suspend fun deleteKey()
}

suspend fun loadPublicKey(): String? =
    runCatching {
        KeyStorage.loadPrivateKey()?.let { normalizePrivateKey(it) }?.let {
            derivePublicKey(it.fromHex()).toHex()
        }
    }.getOrNull()
