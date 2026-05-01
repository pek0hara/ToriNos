package com.nostr.torinos.crypto

actual object KeyStorage {
    actual suspend fun savePrivateKey(hexKey: String) = Unit
    actual suspend fun loadPrivateKey(): String? = null
    actual suspend fun hasKey(): Boolean = false
    actual suspend fun listAccounts(): List<StoredAccount> = emptyList()
    actual suspend fun switchAccount(pubkeyHex: String) = Unit
    actual suspend fun deleteKey() = Unit
}
