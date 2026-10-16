package com.nostr.torinos.account

import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.normalizePrivateKey
import com.nostr.torinos.crypto.toHex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 秘密鍵以外の署名方式で、アカウントごとに安全な保存先へ置く情報。
 * 秘密にあたる値（NIP-46 の通信用の鍵、パスキーで暗号化した秘密鍵）を含むので、
 * 秘密鍵と同じ保存先（Android は Keystore で暗号化、iOS は Keychain）にだけ置く。
 */
@Serializable
sealed class SignerCredential {
    abstract val method: SignerMethod

    @Serializable
    @SerialName("nip07")
    data object Nip07 : SignerCredential() {
        override val method: SignerMethod get() = SignerMethod.Nip07
    }

    @Serializable
    @SerialName("nip46")
    data class Nip46(
        /** 署名器との通信だけに使う鍵（hex）。漏れてもユーザーの鍵では署名できない。 */
        val clientSecretKey: String,
        val remoteSignerPubkey: String,
        val relays: List<String>,
    ) : SignerCredential() {
        override val method: SignerMethod get() = SignerMethod.Nip46
    }

    @Serializable
    @SerialName("passkey")
    data class Passkey(
        /** パスキーの credential ID（base64url）。 */
        val credentialId: String,
        /** PRF に渡す salt（base64url）。 */
        val prfSalt: String,
        /** AES-256-GCM の IV（base64url）。 */
        val iv: String,
        /** 暗号化した秘密鍵（base64url）。 */
        val ciphertext: String,
    ) : SignerCredential() {
        override val method: SignerMethod get() = SignerMethod.Passkey
    }
}

/** アカウントの保存値を読み解いたもの。 */
sealed interface AccountSecret {
    val method: SignerMethod

    data class PrivateKey(val hex: String) : AccountSecret {
        override val method: SignerMethod get() = SignerMethod.PrivateKey
        override fun toString(): String = "PrivateKey(***)"
    }

    data class External(val credential: SignerCredential) : AccountSecret {
        override val method: SignerMethod get() = credential.method
        override fun toString(): String = "External(${credential.method})"
    }
}

/**
 * KeyStorage に保存する文字列との変換。
 * 秘密鍵は従来どおり hex のまま保存し（既存アカウントの移行は不要）、それ以外は接頭辞つきの JSON にする。
 */
object AccountSecretCodec {
    private const val EXTERNAL_PREFIX = "torinos-signer:v1:"
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun encode(credential: SignerCredential): String =
        EXTERNAL_PREFIX + json.encodeToString(SignerCredential.serializer(), credential)

    fun isExternal(stored: String): Boolean = stored.startsWith(EXTERNAL_PREFIX)

    /** [pubkeyHex] のアカウントの保存値として有効なら読み解く。秘密鍵は公開鍵との一致も確かめる。 */
    fun decode(pubkeyHex: String, stored: String): AccountSecret? =
        if (isExternal(stored)) {
            runCatching {
                AccountSecret.External(
                    json.decodeFromString(SignerCredential.serializer(), stored.removePrefix(EXTERNAL_PREFIX)),
                )
            }.getOrNull()
        } else {
            runCatching {
                val normalized = normalizePrivateKey(stored)
                normalized.takeIf { derivePublicKey(it.fromHex()).toHex() == pubkeyHex }?.let(AccountSecret::PrivateKey)
            }.getOrNull()
        }

    /** 保存値を正規化する。無効なら null。秘密鍵は正規化した hex、それ以外は保存値のまま。 */
    fun normalizeStored(pubkeyHex: String, stored: String): String? =
        when (val secret = decode(pubkeyHex, stored)) {
            is AccountSecret.PrivateKey -> secret.hex
            is AccountSecret.External -> stored
            null -> null
        }

    fun methodOf(pubkeyHex: String, stored: String): SignerMethod? = decode(pubkeyHex, stored)?.method
}
