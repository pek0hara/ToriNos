package com.nostr.torinos.account

/** プラットフォームによって使える署名方式の窓口。 */
internal expect object PlatformSigners {
    /** NIP-07（ブラウザ拡張）でログインできる環境か。拡張の読み込みを少し待ってから判定する。 */
    suspend fun isNip07Available(): Boolean

    /** NIP-07 の拡張から公開鍵を受け取る。拡張が確認を求めることがある。 */
    suspend fun nip07PublicKey(): String

    /** 秘密鍵をパスキーで暗号化して保存する環境か（Web）。 */
    val protectsPrivateKeyWithPasskey: Boolean

    /** パスキーを作り、その鍵で秘密鍵を暗号化する。ボタンの操作から呼ぶ。 */
    suspend fun protectWithPasskey(pubkey: String, privateKeyHex: String, userName: String): SignerCredential.Passkey

    /** このページで解錠済みの秘密鍵。解錠していなければ null。 */
    fun unlockedPrivateKey(pubkey: String): String?

    /** ログアウトしたアカウントの、解錠済みの秘密鍵をメモリから消す。 */
    fun forgetUnlockedKey(pubkey: String)
}
