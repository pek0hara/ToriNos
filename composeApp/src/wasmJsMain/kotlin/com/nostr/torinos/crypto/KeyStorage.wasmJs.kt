package com.nostr.torinos.crypto

import kotlinx.browser.localStorage

private object LocalStorageKeyValueStore : KeyValueStore {
    override fun get(key: String): String? = localStorage.getItem(key)

    override fun set(key: String, value: String) = localStorage.setItem(key, value)

    override fun remove(key: String) = localStorage.removeItem(key)
}

/** 秘密鍵はこのブラウザの `localStorage` に保存する(設計の D2)。 */
actual object KeyStorage {
    private val store = BrowserKeyStore(LocalStorageKeyValueStore)

    actual suspend fun savePrivateKey(hexKey: String) = store.savePrivateKey(hexKey)
    actual suspend fun loadPrivateKey(): String? = store.loadPrivateKey()
    actual suspend fun hasKey(): Boolean = store.loadPrivateKey() != null
    actual suspend fun listAccounts(): List<StoredAccount> = store.listAccounts()
    actual suspend fun switchAccount(pubkeyHex: String) = store.switchAccount(pubkeyHex)
    actual suspend fun logout() = store.logout()
    actual suspend fun deleteAccount(pubkeyHex: String) = store.deleteAccount(pubkeyHex)
    actual suspend fun deleteKey() = store.deleteKey()
}
