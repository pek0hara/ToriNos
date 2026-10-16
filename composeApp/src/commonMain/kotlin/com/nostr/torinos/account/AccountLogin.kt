package com.nostr.torinos.account

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.hexToNpub
import com.nostr.torinos.crypto.normalizePrivateKey
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.util.signerTraceLog

/** ログイン方法ごとに、アカウントを保存して読み戻しを確かめる。セッションの開始は呼び出し元で行う。 */
object AccountLogin {
    /** Web で秘密鍵をパスキーで暗号化して保存するか。 */
    val protectsPrivateKeyWithPasskey: Boolean get() = PlatformSigners.protectsPrivateKeyWithPasskey

    /**
     * 秘密鍵のアカウントを保存し、公開鍵を返す。
     * Web はパスキーで暗号化して保存する（生体認証のダイアログが出るので、ボタンの操作から呼ぶ）。
     */
    suspend fun savePrivateKeyAccount(privateKey: String): String {
        val normalized = normalizePrivateKey(privateKey)
        val pubkey = derivePublicKey(normalized.fromHex()).toHex()
        signerTraceLog { "save private key account pubkey=${pubkey.take(8)} passkey=${PlatformSigners.protectsPrivateKeyWithPasskey}" }
        if (PlatformSigners.protectsPrivateKeyWithPasskey) {
            saveExternalAccount(pubkey, PlatformSigners.protectWithPasskey(pubkey, normalized, passkeyUserName(pubkey)))
        } else {
            KeyStorage.savePrivateKey(normalized)
            val saved = KeyStorage.loadPrivateKey() ?: error("保存した秘密鍵を読み戻せませんでした")
            check(normalizePrivateKey(saved) == normalized) { "保存した秘密鍵が一致しません" }
        }
        return pubkey
    }

    suspend fun isNip07Available(): Boolean = PlatformSigners.isNip07Available()

    /** ブラウザ拡張から公開鍵を受け取り、NIP-07 のアカウントとして保存する。 */
    suspend fun saveNip07Account(): String {
        val pubkey = PlatformSigners.nip07PublicKey()
        saveExternalAccount(pubkey, SignerCredential.Nip07)
        return pubkey
    }

    /** 秘密鍵以外の署名方式のアカウントを保存する。 */
    suspend fun saveExternalAccount(pubkey: String, credential: SignerCredential) {
        if (credential !is SignerCredential.Passkey) checkNoStoredKey(pubkey)
        signerTraceLog { "save account pubkey=${pubkey.take(8)} method=${credential.method}" }
        KeyStorage.saveAccountSecret(pubkey, AccountSecretCodec.encode(credential))
        val saved = KeyStorage.loadActiveAccountSecret() ?: error("保存したアカウントを読み戻せませんでした")
        check(saved.pubkeyHex == pubkey && AccountSecretCodec.decode(pubkey, saved.secret) == AccountSecret.External(credential)) {
            "保存したアカウントが一致しません"
        }
    }

    /**
     * Web で暗号化せずに保存している秘密鍵を、パスキーで暗号化した保存へ置き換える。
     * 動いているセッションの署名器はそのまま使い続け、次の読み込みからパスキーの署名器になる。
     */
    suspend fun protectStoredPrivateKey(pubkey: String) {
        check(PlatformSigners.protectsPrivateKeyWithPasskey) { "この環境ではパスキーで保護しません" }
        val stored = KeyStorage.loadActiveAccountSecret()
        check(stored?.pubkeyHex == pubkey) { "保護するアカウントが現在のアカウントと一致しません" }
        val secret = AccountSecretCodec.decode(pubkey, stored!!.secret) as? AccountSecret.PrivateKey
            ?: error("このアカウントは秘密鍵を保存していません")
        saveExternalAccount(pubkey, PlatformSigners.protectWithPasskey(pubkey, secret.hex, passkeyUserName(pubkey)))
    }

    /**
     * 同じアカウントを秘密鍵（パスキーで保護したものを含む）で保存済みなら、拡張機能・リモート署名で上書きしない。
     * 上書きすると、ToriNos に保存していた秘密鍵が消えてしまう。
     */
    private suspend fun checkNoStoredKey(pubkey: String) {
        val existing = KeyStorage.listAccounts().firstOrNull { it.pubkeyHex == pubkey } ?: return
        if (existing.method == SignerMethod.PrivateKey || existing.method == SignerMethod.Passkey) {
            signerTraceLog { "refuse to overwrite stored key pubkey=${pubkey.take(8)} existing=${existing.method}" }
            throw IllegalStateException(
                "このアカウントは秘密鍵で保存済みです。秘密鍵を残すため、リモート署名や拡張機能では上書きしません。" +
                    "切り替えるには、先にログアウトしてから保存済みアカウントを削除してください",
            )
        }
    }

    /** パスワードマネージャーの一覧で見分けられるよう、npub の先頭を付けた名前。 */
    private fun passkeyUserName(pubkey: String): String =
        "ToriNos " + runCatching { hexToNpub(pubkey).take(20) }.getOrDefault(pubkey.take(16))
}
