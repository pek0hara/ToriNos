package com.nostr.torinos.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nostr.torinos.ToriNosApp
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "torinos_secure_keys")

actual object KeyStorage {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "torinos_private_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    private val PREF_ENCRYPTED = stringPreferencesKey("encrypted_private_key")
    private val PREF_IV = stringPreferencesKey("encrypted_private_key_iv")
    private val PREF_ACCOUNTS = stringSetPreferencesKey("encrypted_accounts")
    private val PREF_ACTIVE_PUBKEY = stringPreferencesKey("active_pubkey")
    private val PREF_LOGGED_OUT = androidx.datastore.preferences.core.booleanPreferencesKey("logged_out")
    private val PREF_LOGGED_OUT_ACCOUNTS = stringSetPreferencesKey("logged_out_accounts")

    private val context get() = ToriNosApp.appContext
    private fun keyStore() = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }

    private fun getOrCreateKeystoreKey(): javax.crypto.SecretKey {
        val keyStore = keyStore()
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }
        return (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun deleteKeystoreKey() {
        runCatching {
            val keyStore = keyStore()
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                keyStore.deleteEntry(KEYSTORE_ALIAS)
            }
        }
    }

    private fun encryptPrivateKey(normalized: String): Pair<String, String> =
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).also {
                it.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
            }
            Base64.encodeToString(cipher.doFinal(normalized.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        }.recoverCatching {
            // Keystore が失効している端末では古い alias を消して再生成する。
            deleteKeystoreKey()
            val cipher = Cipher.getInstance(TRANSFORMATION).also {
                it.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
            }
            Base64.encodeToString(cipher.doFinal(normalized.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        }.getOrThrow()

    private fun decryptPrivateKey(encryptedB64: String, ivB64: String): String? =
        try {
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).also {
                it.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            }
            val decrypted = String(cipher.doFinal(Base64.decode(encryptedB64, Base64.NO_WRAP)), Charsets.UTF_8)
            val normalized = normalizePrivateKey(decrypted)
            derivePublicKey(normalized.fromHex())
            normalized
        } catch (_: Exception) {
            null
        }

    private fun accountRecord(pubkeyHex: String, encryptedB64: String, ivB64: String): String =
        listOf(pubkeyHex, encryptedB64, ivB64).joinToString("|")

    private fun parseAccountRecord(record: String): Triple<String, String, String>? {
        val parts = record.split("|")
        if (parts.size != 3) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    private fun accountPubkeys(prefs: androidx.datastore.preferences.core.Preferences): Set<String> =
        prefs[PREF_ACCOUNTS].orEmpty().mapNotNullTo(linkedSetOf()) { parseAccountRecord(it)?.first }

    private fun loggedOutPubkeys(prefs: androidx.datastore.preferences.core.Preferences): Set<String> =
        prefs[PREF_LOGGED_OUT_ACCOUNTS]
            ?: if (prefs[PREF_LOGGED_OUT] == true) accountPubkeys(prefs) else emptySet()

    private fun writeLoggedOutPubkeys(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        pubkeys: Set<String>,
    ) {
        if (pubkeys.isEmpty()) prefs.remove(PREF_LOGGED_OUT_ACCOUNTS)
        else prefs[PREF_LOGGED_OUT_ACCOUNTS] = pubkeys
        prefs.remove(PREF_LOGGED_OUT)
    }

    private suspend fun migrateLegacyKeyIfNeeded() {
        val prefs = runCatching { context.dataStore.data.first() }.getOrElse { emptyPreferences() }
        if (!prefs[PREF_ACCOUNTS].isNullOrEmpty()) return
        val encryptedB64 = prefs[PREF_ENCRYPTED] ?: return
        val ivB64 = prefs[PREF_IV] ?: return
        val normalized = decryptPrivateKey(encryptedB64, ivB64) ?: return
        val pubkeyHex = derivePublicKey(normalized.fromHex()).toHex()
        val encryptedPayload = encryptPrivateKey(normalized)
        context.dataStore.edit { editPrefs ->
            editPrefs[PREF_ACCOUNTS] = setOf(accountRecord(pubkeyHex, encryptedPayload.first, encryptedPayload.second))
            editPrefs[PREF_ACTIVE_PUBKEY] = pubkeyHex
            editPrefs.remove(PREF_ENCRYPTED)
            editPrefs.remove(PREF_IV)
        }
    }

    actual suspend fun savePrivateKey(hexKey: String) {
        val normalized = normalizePrivateKey(hexKey)
        val privateKeyBytes = normalized.fromHex()
        val pubkeyHex = derivePublicKey(privateKeyBytes).toHex()
        val encryptedPayload = encryptPrivateKey(normalized)

        context.dataStore.edit { prefs ->
            val existing = prefs[PREF_ACCOUNTS].orEmpty()
                .filterNot { parseAccountRecord(it)?.first == pubkeyHex }
                .toSet()
            prefs[PREF_ACCOUNTS] = existing + accountRecord(pubkeyHex, encryptedPayload.first, encryptedPayload.second)
            prefs[PREF_ACTIVE_PUBKEY] = pubkeyHex
            writeLoggedOutPubkeys(prefs, loggedOutPubkeys(prefs) - pubkeyHex)
            prefs.remove(PREF_ENCRYPTED)
            prefs.remove(PREF_IV)
        }
    }

    actual suspend fun loadPrivateKey(): String? {
        migrateLegacyKeyIfNeeded()
        val prefs = runCatching { context.dataStore.data.first() }.getOrElse { emptyPreferences() }
        val records = prefs[PREF_ACCOUNTS].orEmpty()
        val parsedRecords = records.mapNotNull(::parseAccountRecord)
        val loggedOut = loggedOutPubkeys(prefs)
        val preferredPubkey = prefs[PREF_ACTIVE_PUBKEY]
        val activePubkey = preferredPubkey?.takeIf { it !in loggedOut }
            ?: parsedRecords.firstOrNull { it.first !in loggedOut }?.first
        val activeRecord = records
            .mapNotNull(::parseAccountRecord)
            .firstOrNull { it.first == activePubkey }
            ?: return null

        val normalized = decryptPrivateKey(activeRecord.second, activeRecord.third)
        return normalized
    }

    actual suspend fun hasKey(): Boolean = loadPrivateKey() != null

    actual suspend fun listAccounts(): List<StoredAccount> {
        migrateLegacyKeyIfNeeded()
        val prefs = runCatching { context.dataStore.data.first() }.getOrElse { emptyPreferences() }
        val activePubkey = prefs[PREF_ACTIVE_PUBKEY]
        val loggedOut = loggedOutPubkeys(prefs)
        val accounts = prefs[PREF_ACCOUNTS].orEmpty()
            .mapNotNull { record ->
                val pubkeyHex = parseAccountRecord(record)?.first ?: return@mapNotNull null
                StoredAccount(
                    pubkeyHex = pubkeyHex,
                    npub = hexToNpub(pubkeyHex),
                    isLoggedOut = pubkeyHex in loggedOut,
                )
            }
            .distinctBy { it.pubkeyHex }
        return accounts.sortedBy { if (it.pubkeyHex == activePubkey) 0 else 1 }
    }

    actual suspend fun switchAccount(pubkeyHex: String) {
        migrateLegacyKeyIfNeeded()
        context.dataStore.edit { prefs ->
            val exists = prefs[PREF_ACCOUNTS].orEmpty()
                .mapNotNull(::parseAccountRecord)
                .any { it.first == pubkeyHex }
            check(exists) { "アカウントが保存されていません" }
            prefs[PREF_ACTIVE_PUBKEY] = pubkeyHex
            writeLoggedOutPubkeys(prefs, loggedOutPubkeys(prefs) - pubkeyHex)
        }
    }

    actual suspend fun logout() {
        migrateLegacyKeyIfNeeded()
        context.dataStore.edit { prefs ->
            val accounts = accountPubkeys(prefs)
            val activePubkey = prefs[PREF_ACTIVE_PUBKEY]
            val loggedOut = loggedOutPubkeys(prefs) + listOfNotNull(activePubkey)
            writeLoggedOutPubkeys(prefs, loggedOut)
            accounts.firstOrNull { it !in loggedOut }?.let {
                prefs[PREF_ACTIVE_PUBKEY] = it
            } ?: prefs.remove(PREF_ACTIVE_PUBKEY)
        }
    }

    actual suspend fun deleteAccount(pubkeyHex: String) {
        migrateLegacyKeyIfNeeded()
        context.dataStore.edit { prefs ->
            val activePubkey = prefs[PREF_ACTIVE_PUBKEY]
            val currentLoggedOut = loggedOutPubkeys(prefs)
            check(pubkeyHex in currentLoggedOut) { "ログアウトしていないアカウントは削除できません" }
            val loggedOut = currentLoggedOut - pubkeyHex
            val remaining = prefs[PREF_ACCOUNTS].orEmpty()
                .filterNot { parseAccountRecord(it)?.first == pubkeyHex }
                .toSet()
            check(remaining.size < prefs[PREF_ACCOUNTS].orEmpty().size) {
                "削除するアカウントが保存されていません"
            }
            if (remaining.isEmpty()) {
                prefs.remove(PREF_ACCOUNTS)
                prefs.remove(PREF_ACTIVE_PUBKEY)
                prefs.remove(PREF_ENCRYPTED)
                prefs.remove(PREF_IV)
                writeLoggedOutPubkeys(prefs, emptySet())
                deleteKeystoreKey()
            } else {
                prefs[PREF_ACCOUNTS] = remaining
                if (activePubkey == pubkeyHex) {
                    val remainingPubkeys = remaining.mapNotNull { parseAccountRecord(it)?.first }
                    remainingPubkeys.firstOrNull { it !in loggedOut }?.let {
                        prefs[PREF_ACTIVE_PUBKEY] = it
                    } ?: prefs.remove(PREF_ACTIVE_PUBKEY)
                }
                writeLoggedOutPubkeys(prefs, loggedOut)
            }
        }
    }

    actual suspend fun deleteKey() {
        migrateLegacyKeyIfNeeded()
        context.dataStore.edit { prefs ->
            val activePubkey = prefs[PREF_ACTIVE_PUBKEY]
            val loggedOut = loggedOutPubkeys(prefs) - listOfNotNull(activePubkey)
            val remaining = prefs[PREF_ACCOUNTS].orEmpty()
                .filterNot { parseAccountRecord(it)?.first == activePubkey }
                .toSet()
            if (remaining.isEmpty()) {
                prefs.remove(PREF_ACCOUNTS)
                prefs.remove(PREF_ACTIVE_PUBKEY)
                prefs.remove(PREF_ENCRYPTED)
                prefs.remove(PREF_IV)
                writeLoggedOutPubkeys(prefs, emptySet())
                deleteKeystoreKey()
            } else {
                prefs[PREF_ACCOUNTS] = remaining
                val remainingPubkeys = remaining.mapNotNull { parseAccountRecord(it)?.first }
                remainingPubkeys.firstOrNull { it !in loggedOut }?.let {
                    prefs[PREF_ACTIVE_PUBKEY] = it
                } ?: prefs.remove(PREF_ACTIVE_PUBKEY)
                writeLoggedOutPubkeys(prefs, loggedOut)
            }
        }
    }
}
