package com.nostr.torinos.account

import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.normalizePrivateKey
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.util.signerTraceLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

private const val NIP07_TIMEOUT_MILLIS = 60_000L

/** 拡張の承認待ちを含めて、NIP-07 の呼び出しを [SignerException] に揃える。 */
private suspend fun <T> nip07Call(block: suspend () -> T): T =
    try {
        withTimeout(NIP07_TIMEOUT_MILLIS) { block() }
    } catch (_: TimeoutCancellationException) {
        throw SignerException.Timeout()
    } catch (e: WebSignerCallFailed) {
        signerTraceLog { "nip07 failed: ${e.reason}" }
        throw when {
            e.reason == "NO_EXTENSION" -> SignerException.Unavailable("ブラウザ拡張（NIP-07）が見つかりません")
            e.reason.contains("reject", ignoreCase = true) || e.reason.contains("denied", ignoreCase = true) ->
                SignerException.Rejected()
            else -> SignerException.Unavailable("ブラウザ拡張で処理できませんでした: ${e.reason}", e)
        }
    }

/** ブラウザ拡張（`window.nostr`）に署名と暗号化を頼む。 */
internal class Nip07AccountSigner(
    override val pubkey: String,
    private val lease: AccountSessionLease,
) : AccountSigner {
    override val method: SignerMethod get() = SignerMethod.Nip07

    override val capabilities: Set<SignerCapability>
        get() = buildSet {
            if (Nip07Js.hasNip44()) add(SignerCapability.Nip44)
            if (Nip07Js.hasNip04()) add(SignerCapability.Nip04)
        }

    override suspend fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?): NostrEvent {
        lease.ensureActive()
        val request = UnsignedEvent(pubkey, createdAt ?: currentEpochSeconds(), kind, tags, content)
        signerTraceLog { "nip07 sign kind=$kind" }
        val signed = parseSignedEventJson(nip07Call { Nip07Js.signEvent(request.toJson()) })
        lease.ensureActive()
        return checkSignedResponse(request, signed)
    }

    override suspend fun nip44Encrypt(plaintext: String, peerPubkey: String): String {
        lease.ensureActive()
        requireCapability(SignerCapability.Nip44)
        return nip07Call { Nip07Js.nip44Encrypt(peerPubkey, plaintext) }
    }

    override suspend fun nip44Decrypt(payload: String, peerPubkey: String): String {
        lease.ensureActive()
        requireCapability(SignerCapability.Nip44)
        return nip07Call { Nip07Js.nip44Decrypt(peerPubkey, payload) }
    }

    override suspend fun nip04Encrypt(plaintext: String, peerPubkey: String): String {
        lease.ensureActive()
        requireCapability(SignerCapability.Nip04)
        return nip07Call { Nip07Js.nip04Encrypt(peerPubkey, plaintext) }
    }

    override suspend fun nip04Decrypt(payload: String, peerPubkey: String): String {
        lease.ensureActive()
        requireCapability(SignerCapability.Nip04)
        return nip07Call { Nip07Js.nip04Decrypt(peerPubkey, payload) }
    }

    private fun requireCapability(capability: SignerCapability) {
        if (capability !in capabilities) throw SignerException.Unsupported(capability)
    }
}

/** パスキーの呼び出しの失敗を、画面に出せる [SignerException] にする。 */
private fun passkeyFailure(e: WebSignerCallFailed): SignerException = when (e.reason) {
    "PRF_UNSUPPORTED" -> SignerException.Unavailable(
        "このブラウザまたはパスキーの保存先は、秘密鍵の保護（PRF）に対応していません。" +
            "ブラウザ拡張（NIP-07）またはリモート署名（NIP-46）でログインしてください",
    )
    "NotAllowedError", "AbortError" -> SignerException.Rejected()
    "SecurityError" -> SignerException.Unavailable("このページのアドレスではパスキーを使えません")
    else -> SignerException.Unavailable("パスキーを使えませんでした: ${e.reason}", e)
}

/**
 * パスキーで解錠した秘密鍵を、このページを開いている間だけメモリに置く。
 * アカウントを切り替えて戻るたびに生体認証を求めないため。ログアウトで消す。
 */
internal object UnlockedPasskeyKeys {
    private val keys = mutableMapOf<String, String>()

    fun get(pubkey: String): String? = keys[pubkey]

    fun put(pubkey: String, privateKeyHex: String) {
        keys[pubkey] = privateKeyHex
    }

    fun remove(pubkey: String) {
        keys.remove(pubkey)
    }
}

/** 作成したが、まだ秘密鍵の暗号化まで済んでいないパスキー。もう一度押したときに作り直さない。 */
private class PendingPasskey(val pubkey: String, val credentialId: String, val salt: String)

private var pendingPasskey: PendingPasskey? = null

internal object PasskeyVault {
    /** パスキーを作り（作成済みなら使い）、その鍵で秘密鍵を暗号化する。 */
    suspend fun protect(pubkey: String, privateKeyHex: String, userName: String): SignerCredential.Passkey {
        if (!PasskeyJs.isSupported()) {
            throw SignerException.Unavailable("このブラウザではパスキーを使えません")
        }
        val normalized = normalizePrivateKey(privateKeyHex)
        check(derivePublicKey(normalized.fromHex()).toHex() == pubkey) { "秘密鍵と公開鍵が一致しません" }
        try {
            val pending = pendingPasskey?.takeIf { it.pubkey == pubkey }
            val (credentialId, salt, createdKey) = if (pending != null) {
                Triple(pending.credentialId, pending.salt, null)
            } else {
                val salt = PasskeyJs.newSalt()
                val created = PasskeyJs.create(userName, salt)
                pendingPasskey = PendingPasskey(pubkey, created.credentialId, salt)
                Triple(created.credentialId, salt, created.key)
            }
            // 作成時に PRF の出力を返さない環境では、作ったパスキーをもう一度使って鍵を得る。
            val key = createdKey ?: PasskeyJs.unlock(credentialId, salt)
            val (iv, ciphertext) = PasskeyJs.encrypt(key, normalized)
            // 読み戻して一致を確かめてから保存させる。
            check(PasskeyJs.decrypt(key, iv, ciphertext) == normalized) { "パスキーで暗号化した鍵を読み戻せませんでした" }
            pendingPasskey = null
            UnlockedPasskeyKeys.put(pubkey, normalized)
            return SignerCredential.Passkey(credentialId = credentialId, prfSalt = salt, iv = iv, ciphertext = ciphertext)
        } catch (e: WebSignerCallFailed) {
            throw passkeyFailure(e)
        }
    }

    /** パスキーで秘密鍵を復号する。 */
    suspend fun unlock(pubkey: String, credential: SignerCredential.Passkey): String {
        try {
            val key = PasskeyJs.unlock(credential.credentialId, credential.prfSalt)
            val decrypted = PasskeyJs.decrypt(key, credential.iv, credential.ciphertext)
            val normalized = normalizePrivateKey(decrypted)
            if (derivePublicKey(normalized.fromHex()).toHex() != pubkey) {
                throw SignerException.Unavailable("パスキーで復号した鍵がアカウントと一致しません")
            }
            UnlockedPasskeyKeys.put(pubkey, normalized)
            return normalized
        } catch (e: WebSignerCallFailed) {
            // AES-GCM の復号の失敗（別のパスキーを選んだなど）もここに来る。
            throw if (e.reason == "OperationError") {
                SignerException.Unavailable("このパスキーでは解錠できません。保存したときのパスキーを選んでください")
            } else {
                passkeyFailure(e)
            }
        }
    }
}

/**
 * パスキーで暗号化した秘密鍵を、解錠してから使う。
 * 解錠するまでは読むことだけができ、署名・暗号化は [SignerException.Locked] になる。
 */
internal class PasskeyAccountSigner(
    override val pubkey: String,
    private val credential: SignerCredential.Passkey,
    private val lease: AccountSessionLease,
) : AccountSigner, InteractiveSigner {
    private val unlockMutex = Mutex()
    private var unlocked: PrivateKeyAccountSigner? =
        UnlockedPasskeyKeys.get(pubkey)?.let { PrivateKeyAccountSigner(it, lease) }
    private val _attention = MutableStateFlow<SignerAttention?>(if (unlocked == null) SignerAttention.Locked else null)
    override val attention: StateFlow<SignerAttention?> = _attention.asStateFlow()

    override val method: SignerMethod get() = SignerMethod.Passkey

    override suspend fun unlock() {
        unlockMutex.withLock {
            if (unlocked != null) return
            lease.ensureActive()
            signerTraceLog { "passkey unlock start pubkey=${pubkey.take(8)}" }
            val key = PasskeyVault.unlock(pubkey, credential)
            signerTraceLog { "passkey unlocked pubkey=${pubkey.take(8)}" }
            unlocked = PrivateKeyAccountSigner(key, lease)
            _attention.value = null
        }
    }

    private fun inner(): PrivateKeyAccountSigner {
        lease.ensureActive()
        return unlocked ?: throw SignerException.Locked()
    }

    override suspend fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?): NostrEvent =
        inner().sign(content, kind, tags, createdAt)

    override suspend fun nip44Encrypt(plaintext: String, peerPubkey: String): String =
        inner().nip44Encrypt(plaintext, peerPubkey)

    override suspend fun nip44Decrypt(payload: String, peerPubkey: String): String =
        inner().nip44Decrypt(payload, peerPubkey)
}

internal actual fun createPlatformSigner(
    pubkey: String,
    credential: SignerCredential,
    lease: AccountSessionLease,
): AccountSigner? = when (credential) {
    SignerCredential.Nip07 -> Nip07AccountSigner(pubkey, lease)
    is SignerCredential.Passkey -> PasskeyAccountSigner(pubkey, credential, lease)
    is SignerCredential.Nip46 -> null
}

internal actual object PlatformSigners {
    actual suspend fun isNip07Available(): Boolean {
        // 拡張はページの読み込み後に window.nostr を差し込むことがあるので、最大1秒待つ。
        repeat(10) {
            if (Nip07Js.isAvailable()) return true
            delay(100)
        }
        return Nip07Js.isAvailable()
    }

    actual suspend fun nip07PublicKey(): String {
        val pubkey = nip07Call { Nip07Js.getPublicKey() }.trim().lowercase()
        if (pubkey.length != 64 || !pubkey.all { it in '0'..'9' || it in 'a'..'f' }) {
            throw SignerException.InvalidResponse("公開鍵の形式が正しくありません")
        }
        return pubkey
    }

    actual val protectsPrivateKeyWithPasskey: Boolean = true

    actual suspend fun protectWithPasskey(
        pubkey: String,
        privateKeyHex: String,
        userName: String,
    ): SignerCredential.Passkey = PasskeyVault.protect(pubkey, privateKeyHex, userName)

    actual fun unlockedPrivateKey(pubkey: String): String? = UnlockedPasskeyKeys.get(pubkey)

    actual fun forgetUnlockedKey(pubkey: String) = UnlockedPasskeyKeys.remove(pubkey)
}
