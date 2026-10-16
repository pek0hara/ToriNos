package com.nostr.torinos.crypto

import com.nostr.torinos.account.AccountSecretCodec
import com.nostr.torinos.account.SignerMethod

data class StoredAccount(
    val pubkeyHex: String,
    val npub: String,
    val isLoggedOut: Boolean = false,
    val method: SignerMethod = SignerMethod.PrivateKey,
)

/** アクティブなアカウントの公開鍵と保存値（秘密鍵の hex、または [AccountSecretCodec] の形式）。 */
data class StoredAccountSecret(
    val pubkeyHex: String,
    val secret: String,
) {
    override fun toString(): String = "StoredAccountSecret(pubkeyHex=$pubkeyHex, secret=***)"
}

/**
 * アカウントごとに1つの保存値を、安全な保存先へ置く。
 * 保存値は秘密鍵の hex か、秘密鍵以外の署名方式の情報（[AccountSecretCodec]）。
 */
expect object KeyStorage {
    suspend fun savePrivateKey(hexKey: String)
    /** アクティブなアカウントの秘密鍵。秘密鍵以外の署名方式のアカウントでは null。 */
    suspend fun loadPrivateKey(): String?
    suspend fun hasKey(): Boolean
    /** [pubkeyHex] のアカウントとして [secret] を保存し、アクティブにする。 */
    suspend fun saveAccountSecret(pubkeyHex: String, secret: String)
    suspend fun loadActiveAccountSecret(): StoredAccountSecret?
    suspend fun listAccounts(): List<StoredAccount>
    suspend fun switchAccount(pubkeyHex: String)
    suspend fun logout()
    /** 指定したログアウト済みアカウントと、アプリが作成した同期バックアップを削除する。 */
    suspend fun deleteAccount(pubkeyHex: String)
    suspend fun deleteKey()
}

suspend fun loadPublicKey(): String? =
    runCatching { KeyStorage.loadActiveAccountSecret()?.pubkeyHex }.getOrNull()

/** 保存値がアクティブなアカウントとして使えるときの署名方式。使えなければ null。 */
internal fun storedSecretMethod(pubkeyHex: String, stored: String): SignerMethod? =
    AccountSecretCodec.methodOf(pubkeyHex, stored)

internal fun privateKeyOrNull(secret: StoredAccountSecret?): String? =
    secret?.secret?.takeUnless(AccountSecretCodec::isExternal)
