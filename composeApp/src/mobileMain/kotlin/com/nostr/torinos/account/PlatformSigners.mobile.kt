package com.nostr.torinos.account

internal actual object PlatformSigners {
    actual suspend fun isNip07Available(): Boolean = false

    actual suspend fun nip07PublicKey(): String =
        throw SignerException.Unavailable("ブラウザ拡張でのログインは Web 版だけで使えます")

    // ネイティブは Keystore・Keychain で保護しているので、パスキーは使わない。
    actual val protectsPrivateKeyWithPasskey: Boolean = false

    actual suspend fun protectWithPasskey(
        pubkey: String,
        privateKeyHex: String,
        userName: String,
    ): SignerCredential.Passkey = throw SignerException.Unavailable("パスキーでの保護は Web 版だけで使えます")

    actual fun unlockedPrivateKey(pubkey: String): String? = null

    actual fun forgetUnlockedKey(pubkey: String) = Unit
}
