package com.nostr.torinos.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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

    private suspend fun clearStoredKey() {
        context.dataStore.edit { prefs ->
            prefs.remove(PREF_ENCRYPTED)
            prefs.remove(PREF_IV)
        }
    }

    actual suspend fun savePrivateKey(hexKey: String) {
        val encryptedPayload = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).also {
                it.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
            }
            Base64.encodeToString(cipher.doFinal(hexKey.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        }.recoverCatching {
            // Keystore が失効している端末では古い alias を消して再生成する。
            deleteKeystoreKey()
            val cipher = Cipher.getInstance(TRANSFORMATION).also {
                it.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
            }
            Base64.encodeToString(cipher.doFinal(hexKey.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        }.getOrThrow()

        context.dataStore.edit { prefs ->
            prefs[PREF_ENCRYPTED] = encryptedPayload.first
            prefs[PREF_IV] = encryptedPayload.second
        }
    }

    actual suspend fun loadPrivateKey(): String? {
        val prefs = runCatching { context.dataStore.data.first() }.getOrElse { emptyPreferences() }
        val encryptedB64 = prefs[PREF_ENCRYPTED] ?: return null
        val ivB64 = prefs[PREF_IV] ?: run {
            clearStoredKey()
            return null
        }

        return try {
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).also {
                it.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            }
            String(cipher.doFinal(Base64.decode(encryptedB64, Base64.NO_WRAP)), Charsets.UTF_8).also { hexKey ->
                derivePublicKey(hexKey.fromHex())
            }
        } catch (_: Exception) {
            clearStoredKey()
            deleteKeystoreKey()
            null
        }
    }

    actual suspend fun hasKey(): Boolean = loadPrivateKey() != null

    actual suspend fun deleteKey() {
        clearStoredKey()
        deleteKeystoreKey()
    }
}
