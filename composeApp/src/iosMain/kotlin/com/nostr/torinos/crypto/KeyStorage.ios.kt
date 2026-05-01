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
private const val ACCOUNTS_DEFAULTS_KEY = "torinos_accounts"
private const val ACTIVE_ACCOUNT_DEFAULTS_KEY = "torinos_active_account"

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
            val status = KeychainSaveData(
                KEYCHAIN_SERVICE,
                keychainAccount(pubkeyHex),
                data,
            )
            check(status == 0) { "Keychain add failed (OSStatus=$status)" }
        }
        val defaults = NSUserDefaults.standardUserDefaults
        val accounts = readAccountPubkeys(defaults).filterNot { it == pubkeyHex } + pubkeyHex
        writeAccountPubkeys(defaults, accounts)
        defaults.setObject(pubkeyHex, forKey = ACTIVE_ACCOUNT_DEFAULTS_KEY)
        defaults.synchronize()
    }

    actual suspend fun loadPrivateKey(): String? {
        migrateLegacyKeyIfNeeded()
        val defaults = NSUserDefaults.standardUserDefaults
        val activePubkey = defaults.stringForKey(ACTIVE_ACCOUNT_DEFAULTS_KEY)
            ?: readAccountPubkeys(defaults).firstOrNull()
            ?: return null

        loadPrivateKeyFromKeychain(keychainAccount(activePubkey))?.let { return it }

        val synchronizedKey = loadPrivateKeyFromKeychain(keychainAccount(activePubkey), synchronizable = true)
        if (synchronizedKey != null) {
            savePrivateKey(synchronizedKey)
            return synchronizedKey
        }

        deleteKey()
        return null
    }

    actual suspend fun hasKey(): Boolean = loadPrivateKey() != null

    actual suspend fun listAccounts(): List<StoredAccount> {
        migrateLegacyKeyIfNeeded()
        val defaults = NSUserDefaults.standardUserDefaults
        val activePubkey = defaults.stringForKey(ACTIVE_ACCOUNT_DEFAULTS_KEY)
        return readAccountPubkeys(defaults)
            .map { StoredAccount(pubkeyHex = it, npub = hexToNpub(it)) }
            .sortedBy { if (it.pubkeyHex == activePubkey) 0 else 1 }
    }

    actual suspend fun switchAccount(pubkeyHex: String) {
        val defaults = NSUserDefaults.standardUserDefaults
        check(pubkeyHex in readAccountPubkeys(defaults)) { "アカウントが保存されていません" }
        defaults.setObject(pubkeyHex, forKey = ACTIVE_ACCOUNT_DEFAULTS_KEY)
        defaults.synchronize()
    }

    actual suspend fun deleteKey() {
        migrateLegacyKeyIfNeeded()
        val defaults = NSUserDefaults.standardUserDefaults
        val activePubkey = defaults.stringForKey(ACTIVE_ACCOUNT_DEFAULTS_KEY)
        if (activePubkey != null) {
            deleteKeychainEntries(keychainAccount(activePubkey))
            val remaining = readAccountPubkeys(defaults).filterNot { it == activePubkey }
            writeAccountPubkeys(defaults, remaining)
            if (remaining.isEmpty()) {
                defaults.removeObjectForKey(ACTIVE_ACCOUNT_DEFAULTS_KEY)
            } else {
                defaults.setObject(remaining.first(), forKey = ACTIVE_ACCOUNT_DEFAULTS_KEY)
            }
        }
        defaults.removeObjectForKey(LEGACY_DEFAULTS_KEY)
        defaults.synchronize()
    }

    private suspend fun migrateLegacyKeyIfNeeded() {
        val defaults = NSUserDefaults.standardUserDefaults
        if (readAccountPubkeys(defaults).isNotEmpty()) return

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

    private fun writeAccountPubkeys(defaults: NSUserDefaults, pubkeys: List<String>) {
        if (pubkeys.isEmpty()) {
            defaults.removeObjectForKey(ACCOUNTS_DEFAULTS_KEY)
        } else {
            defaults.setObject(pubkeys.distinct().joinToString(","), forKey = ACCOUNTS_DEFAULTS_KEY)
        }
    }
}
