package com.nostr.torinos.crypto

import com.nostr.torinos.crypto.interop.KeychainDeleteData
import com.nostr.torinos.crypto.interop.KeychainLoadData
import com.nostr.torinos.crypto.interop.KeychainSaveSynchronizableData
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
private const val KEYCHAIN_ACCOUNTS_INDEX_ACCOUNT = "accounts_index"
private const val LEGACY_DEFAULTS_KEY = "torinos_private_key"
private const val ACCOUNTS_DEFAULTS_KEY = "torinos_accounts"
private const val ACTIVE_ACCOUNT_DEFAULTS_KEY = "torinos_active_account"
private const val LOGGED_OUT_DEFAULTS_KEY = "torinos_logged_out"

private fun keychainAccount(pubkeyHex: String): String = "private_key_$pubkeyHex"

@OptIn(ExperimentalForeignApi::class)
private fun loadPrivateKeyFromKeychain(
    account: String = KEYCHAIN_ACCOUNT,
    synchronizable: Boolean? = null,
): String? {
    val nsData = KeychainLoadData(
        KEYCHAIN_SERVICE,
        account,
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

@OptIn(ExperimentalForeignApi::class)
private fun loadStringFromKeychain(
    account: String,
    synchronizable: Boolean? = null,
): String? {
    val nsData = KeychainLoadData(
        KEYCHAIN_SERVICE,
        account,
        includeSynchronizable = synchronizable != null,
        synchronizable = synchronizable ?: false,
    ) ?: return null
    val bytePtr: CPointer<ByteVar> = nsData.bytes?.reinterpret()
        ?: return null
    return ByteArray(nsData.length.toInt()) { i -> bytePtr[i] }.decodeToString()
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun saveSynchronizableString(account: String, value: String) {
    val bytes = value.encodeToByteArray()
    bytes.usePinned { pinned ->
        val data = NSData.create(
            bytes = pinned.addressOf(0),
            length = bytes.size.toULong(),
        )
        val status = KeychainSaveSynchronizableData(
            KEYCHAIN_SERVICE,
            account,
            data,
        )
        check(status == 0) { "Keychain add failed (OSStatus=$status)" }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object KeyStorage {

    actual suspend fun savePrivateKey(hexKey: String) {
        val normalized = normalizePrivateKey(hexKey)
        val pubkeyHex = derivePublicKey(normalized.fromHex()).toHex()

        val bytes = normalized.encodeToByteArray()
        bytes.usePinned { pinned ->
            val data = NSData.create(
                bytes = pinned.addressOf(0),
                length = bytes.size.toULong(),
            )
            val status = KeychainSaveSynchronizableData(
                KEYCHAIN_SERVICE,
                keychainAccount(pubkeyHex),
                data,
            )
            check(status == 0) { "Keychain add failed (OSStatus=$status)" }
        }
        val defaults = NSUserDefaults.standardUserDefaults
        val accounts = readAllAccountPubkeys(defaults).filterNot { it == pubkeyHex } + pubkeyHex
        writeAccountPubkeys(defaults, accounts)
        writeSyncedAccountPubkeys(accounts)
        defaults.setObject(pubkeyHex, forKey = ACTIVE_ACCOUNT_DEFAULTS_KEY)
        defaults.setBool(false, forKey = LOGGED_OUT_DEFAULTS_KEY)
        defaults.synchronize()
    }

    actual suspend fun loadPrivateKey(): String? {
        migrateLegacyKeyIfNeeded()
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.boolForKey(LOGGED_OUT_DEFAULTS_KEY)) return null
        val activePubkey = defaults.stringForKey(ACTIVE_ACCOUNT_DEFAULTS_KEY)
            ?: readAccountPubkeys(defaults).firstOrNull()
            ?: return null

        loadPrivateKeyFromKeychain(keychainAccount(activePubkey))?.let {
            savePrivateKey(it)
            return it
        }

        val synchronizedKey = loadPrivateKeyFromKeychain(keychainAccount(activePubkey), synchronizable = true)
        if (synchronizedKey != null) {
            savePrivateKey(synchronizedKey)
            return synchronizedKey
        }

        return null
    }

    actual suspend fun hasKey(): Boolean = loadPrivateKey() != null

    actual suspend fun listAccounts(): List<StoredAccount> {
        migrateLegacyKeyIfNeeded()
        val defaults = NSUserDefaults.standardUserDefaults
        val activePubkey = defaults.stringForKey(ACTIVE_ACCOUNT_DEFAULTS_KEY)
        val accounts = readAllAccountPubkeys(defaults)
        return accounts
            .map { StoredAccount(pubkeyHex = it, npub = hexToNpub(it)) }
            .sortedBy { if (it.pubkeyHex == activePubkey) 0 else 1 }
    }

    actual suspend fun switchAccount(pubkeyHex: String) {
        val defaults = NSUserDefaults.standardUserDefaults
        check(pubkeyHex in readAllAccountPubkeys(defaults)) { "アカウントが保存されていません" }
        defaults.setObject(pubkeyHex, forKey = ACTIVE_ACCOUNT_DEFAULTS_KEY)
        defaults.setBool(false, forKey = LOGGED_OUT_DEFAULTS_KEY)
        defaults.synchronize()
    }

    actual suspend fun logout() {
        migrateLegacyKeyIfNeeded()
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setBool(true, forKey = LOGGED_OUT_DEFAULTS_KEY)
        defaults.synchronize()
    }

    actual suspend fun deleteKey() {
        migrateLegacyKeyIfNeeded()
        val defaults = NSUserDefaults.standardUserDefaults
        val activePubkey = defaults.stringForKey(ACTIVE_ACCOUNT_DEFAULTS_KEY)
        if (activePubkey != null) {
            deleteKeychainEntries(keychainAccount(activePubkey))
            val remaining = readAllAccountPubkeys(defaults).filterNot { it == activePubkey }
            writeAccountPubkeys(defaults, remaining)
            runCatching { writeSyncedAccountPubkeys(remaining) }
                .onFailure { e ->
                    logException("KeyStorage", e, "Failed to update synced account index while deleting key")
                }
            if (remaining.isEmpty()) {
                defaults.removeObjectForKey(ACTIVE_ACCOUNT_DEFAULTS_KEY)
                defaults.removeObjectForKey(LOGGED_OUT_DEFAULTS_KEY)
            } else {
                defaults.setObject(remaining.first(), forKey = ACTIVE_ACCOUNT_DEFAULTS_KEY)
                defaults.setBool(false, forKey = LOGGED_OUT_DEFAULTS_KEY)
            }
        }
        defaults.removeObjectForKey(LEGACY_DEFAULTS_KEY)
        defaults.synchronize()
    }

    private suspend fun migrateLegacyKeyIfNeeded() {
        val defaults = NSUserDefaults.standardUserDefaults
        if (readAllAccountPubkeys(defaults).isNotEmpty()) return

        // Migrate from NSUserDefaults if a legacy key exists.
        val legacy = defaults.stringForKey(LEGACY_DEFAULTS_KEY)
        if (legacy != null) {
            savePrivateKey(legacy)
            defaults.removeObjectForKey(LEGACY_DEFAULTS_KEY)
            defaults.synchronize()
            deleteKeychainEntries(KEYCHAIN_ACCOUNT)
            return
        }

        loadPrivateKeyFromKeychain()?.let {
            savePrivateKey(it)
            deleteKeychainEntries(KEYCHAIN_ACCOUNT)
            return
        }

        val synchronizedKey = loadPrivateKeyFromKeychain(synchronizable = true)
        if (synchronizedKey != null) {
            savePrivateKey(synchronizedKey)
            deleteKeychainEntries(KEYCHAIN_ACCOUNT)
            return
        }
    }

    private fun deleteKeychainEntries(account: String) {
        KeychainDeleteData(KEYCHAIN_SERVICE, account, includeSynchronizable = false, synchronizable = false)
        KeychainDeleteData(KEYCHAIN_SERVICE, account, includeSynchronizable = true, synchronizable = true)
    }

    private fun readAccountPubkeys(defaults: NSUserDefaults): List<String> =
        defaults.stringForKey(ACCOUNTS_DEFAULTS_KEY)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()

    private fun readSyncedAccountPubkeys(): List<String> =
        loadStringFromKeychain(KEYCHAIN_ACCOUNTS_INDEX_ACCOUNT, synchronizable = true)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()

    private fun readAllAccountPubkeys(defaults: NSUserDefaults): List<String> =
        (readAccountPubkeys(defaults) + readSyncedAccountPubkeys()).distinct()

    private fun writeSyncedAccountPubkeys(pubkeys: List<String>) {
        saveSynchronizableString(KEYCHAIN_ACCOUNTS_INDEX_ACCOUNT, pubkeys.distinct().joinToString(","))
    }

    private fun writeAccountPubkeys(defaults: NSUserDefaults, pubkeys: List<String>) {
        if (pubkeys.isEmpty()) {
            defaults.removeObjectForKey(ACCOUNTS_DEFAULTS_KEY)
        } else {
            defaults.setObject(pubkeys.distinct().joinToString(","), forKey = ACCOUNTS_DEFAULTS_KEY)
        }
    }
}
