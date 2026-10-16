package com.nostr.torinos.account

import com.nostr.torinos.crypto.Nip44
import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.util.signerTraceLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** アカウントの署名をどこで行うか。 */
enum class SignerMethod {
    /** 秘密鍵を端末の安全な保存先に置き、アプリ内で署名する。 */
    PrivateKey,

    /** ブラウザ拡張の `window.nostr` に署名を頼む（Web のみ）。 */
    Nip07,

    /** リモート署名器にリレー経由で署名を頼む。 */
    Nip46,

    /** パスキーで暗号化した秘密鍵を、解錠してからアプリ内で署名する（Web のみ）。 */
    Passkey,
}

enum class SignerCapability { Nip44, Nip04 }

/** 署名器が署名・暗号化を行えなかった理由。画面にはメッセージをそのまま出す。 */
sealed class SignerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Rejected : SignerException("署名が拒否されました")
    class Timeout : SignerException("署名器から応答がありません")
    class Unavailable(reason: String, cause: Throwable? = null) : SignerException(reason, cause)
    class Unsupported(val capability: SignerCapability) : SignerException("この署名方式では使えない操作です")
    class AuthRequired(val url: String) : SignerException("署名器での承認が必要です")
    class Locked : SignerException("パスキーで解錠してから操作してください")
    class InvalidResponse(reason: String) : SignerException("署名器の応答が正しくありません: $reason")
}

/**
 * セッションに固定した署名器。秘密鍵は画面や ViewModel に公開しない。
 *
 * 外部の署名器は「頼んで返事を待つ」ので、署名と暗号化はすべて suspend にする。
 * 失敗は [SignerException] で返す。セッション終了後の呼び出しは [AccountSessionLease] の例外になる。
 */
interface AccountSigner {
    val pubkey: String
    val method: SignerMethod get() = SignerMethod.PrivateKey
    val capabilities: Set<SignerCapability> get() = setOf(SignerCapability.Nip44)

    suspend fun sign(
        content: String,
        kind: Int = 1,
        tags: List<List<String>> = emptyList(),
        createdAt: Long? = null,
    ): NostrEvent

    suspend fun nip44Encrypt(plaintext: String, peerPubkey: String): String

    suspend fun nip44Decrypt(payload: String, peerPubkey: String): String

    /** 旧 DM（kind 4）などの読み書き用。対応しない署名器は [SignerCapability.Nip04] を持たない。 */
    suspend fun nip04Encrypt(plaintext: String, peerPubkey: String): String =
        throw SignerException.Unsupported(SignerCapability.Nip04)

    suspend fun nip04Decrypt(payload: String, peerPubkey: String): String =
        throw SignerException.Unsupported(SignerCapability.Nip04)

    suspend fun encryptToSelf(plaintext: String): String = nip44Encrypt(plaintext, pubkey)

    suspend fun decrypt(content: String, peerPubkey: String): String = nip44Decrypt(content, peerPubkey)
}

/** セッションの間だけ接続などを持つ署名器。セッションの終了で閉じる。 */
interface SessionBoundSigner {
    fun close()
}

/** 外部の署名器へ渡す、署名前のイベント。 */
data class UnsignedEvent(
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
)

private val signerJson = Json { ignoreUnknownKeys = true }

/** NIP-07・NIP-46 に渡す、署名前のイベントの JSON。 */
internal fun UnsignedEvent.toJson(): String = buildJsonObject {
    put("pubkey", pubkey)
    put("created_at", createdAt)
    put("kind", kind)
    put("tags", JsonArray(tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) }))
    put("content", content)
}.toString()

/** 外部の署名器が返した、署名済みイベントの JSON を読む。 */
internal fun parseSignedEventJson(json: String): NostrEvent =
    try {
        signerJson.decodeFromString(NostrEvent.serializer(), json)
    } catch (e: Exception) {
        throw SignerException.InvalidResponse("署名済みイベントを読めません")
    }

/**
 * 外部の署名器が返したイベントが、頼んだ内容そのものに正しく署名されているかを確かめる。
 * 署名器が別の内容に署名して返すことを防ぐ。
 */
internal fun checkSignedResponse(request: UnsignedEvent, signed: NostrEvent): NostrEvent {
    fun fail(reason: String): Nothing = throw SignerException.InvalidResponse(reason)
    if (signed.pubkey != request.pubkey) fail("公開鍵が違います")
    if (signed.kind != request.kind) fail("kind が違います")
    if (signed.content != request.content) fail("本文が違います")
    if (signed.tags != request.tags) fail("タグが違います")
    if (signed.createdAt != request.createdAt) fail("作成日時が違います")
    if (!isValidEvent(signed)) fail("署名が正しくありません")
    return signed
}

internal fun currentEpochSeconds(): Long = kotlin.time.Clock.System.now().epochSeconds

internal class PrivateKeyAccountSigner(
    private val privateKeyHex: String,
    private val lease: AccountSessionLease,
) : AccountSigner {
    override val pubkey: String = derivePublicKey(privateKeyHex.fromHex()).toHex()

    override suspend fun nip44Encrypt(plaintext: String, peerPubkey: String): String {
        lease.ensureActive()
        return Nip44.encrypt(plaintext, privateKeyHex, peerPubkey)
            .also { signerTraceLog { "local nip44Encrypt self=${peerPubkey == pubkey}" } }
    }

    override suspend fun nip44Decrypt(payload: String, peerPubkey: String): String {
        lease.ensureActive()
        return Nip44.decrypt(payload, privateKeyHex, peerPubkey)
            .also { signerTraceLog { "local nip44Decrypt self=${peerPubkey == pubkey}" } }
    }

    override suspend fun sign(
        content: String,
        kind: Int,
        tags: List<List<String>>,
        createdAt: Long?,
    ): NostrEvent {
        lease.ensureActive()
        return if (createdAt == null) {
            signEvent(privateKeyHex = privateKeyHex, content = content, kind = kind, tags = tags)
        } else {
            signEvent(privateKeyHex = privateKeyHex, content = content, kind = kind, tags = tags, createdAt = createdAt)
        }.also { signerTraceLog { "local sign kind=$kind id=${it.id.take(12)}" } }
    }
}

/**
 * 暗号化された内容の読み取りで、壊れた内容は null にし、署名器の失敗とキャンセルは呼び出し元へ伝える。
 * 署名器が応答しないのに「読み込めない内容」と扱うと、件数ぶんタイムアウトを待つうえ、原因も伝わらない。
 */
internal inline fun <T> decodeUnlessSignerFails(block: () -> T): T? =
    try {
        block()
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: SignerException) {
        signerTraceLog { "decode stopped by signer: ${e::class.simpleName}" }
        throw e
    } catch (e: AccountSessionEndedException) {
        throw e
    } catch (e: Exception) {
        signerTraceLog { "decode failed (broken content): ${e::class.simpleName}" }
        null
    }
