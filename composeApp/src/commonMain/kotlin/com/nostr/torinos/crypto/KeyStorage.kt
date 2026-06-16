package com.nostr.torinos.crypto

data class StoredAccount(
    val pubkeyHex: String,
    val npub: String,
)

expect object KeyStorage {
    suspend fun savePrivateKey(hexKey: String)
    suspend fun loadPrivateKey(): String?
    suspend fun hasKey(): Boolean
    suspend fun listAccounts(): List<StoredAccount>
    suspend fun switchAccount(pubkeyHex: String)
    suspend fun logout()
    suspend fun deleteKey()
}

suspend fun loadPublicKey(): String? =
    runCatching {
        KeyStorage.loadPrivateKey()?.let { normalizePrivateKey(it) }?.let {
            derivePublicKey(it.fromHex()).toHex()
        }
    }.getOrNull()
