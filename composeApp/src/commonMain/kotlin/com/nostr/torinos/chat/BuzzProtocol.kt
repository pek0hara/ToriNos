package com.nostr.torinos.chat

import com.nostr.torinos.crypto.sha256
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64

/** NIP-98（HTTP 認証）の kind。 */
internal const val HTTP_AUTH_KIND = 27235

/** Blossom（BUD-01）の認証の kind。 */
internal const val BLOSSOM_AUTH_KIND = 24242

/**
 * Buzz の `/upload` に付けるヘッダー(Content-Type 以外)。Buzz は `X-SHA-256` を認証の `x` タグと照らし合わせ、
 * 無いと 401 で断る。
 */
internal fun buzzUploadHeaders(authorization: String, sha256Hex: String): Map<String, String> = mapOf(
    "Authorization" to authorization,
    "X-SHA-256" to sha256Hex,
)

/** Buzz の参加規約（`GET /api/join-policy`）。 */
data class BuzzJoinPolicy(
    val version: String,
    val termsMarkdown: String?,
    val privacyMarkdown: String?,
    val ageAttestationRequired: Boolean,
)

private val protocolJson = Json { ignoreUnknownKeys = true }

/**
 * `{ policy: {...} }`、または規約が無いとき `{}` / `{ policy: null }`。
 * それ以外の形はエラー（規約が無いとはみなさない。同意を飛ばさないため）。
 */
fun parseBuzzJoinPolicy(body: String): BuzzJoinPolicy? {
    val root = runCatching { protocolJson.parseToJsonElement(body) as JsonObject }.getOrNull()
        ?: throw IllegalArgumentException("参加規約の形が正しくありません")
    val raw = root["policy"]
    if (raw == null || raw is JsonNull) return null
    val policy = raw as? JsonObject ?: throw IllegalArgumentException("参加規約の形が正しくありません")
    fun text(name: String): String? = when (val value = policy[name]) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else throw IllegalArgumentException("参加規約の形が正しくありません")
        else -> throw IllegalArgumentException("参加規約の形が正しくありません")
    }
    val version = (policy["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("参加規約の形が正しくありません")
    val age = (policy["age_attestation_required"] as? JsonPrimitive)?.booleanOrNull
        ?: throw IllegalArgumentException("参加規約の形が正しくありません")
    return BuzzJoinPolicy(version, text("terms_markdown"), text("privacy_markdown"), age)
}

/** 拒否の応答 `{ "error": "<code>" }` を表示用の文にする（Buzz の `CLAIM_REFUSALS` と同じ区分）。 */
fun buzzRefusalMessage(body: String): String {
    val code = runCatching {
        (protocolJson.parseToJsonElement(body).jsonObject["error"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull() ?: return "参加できませんでした"
    return when (code) {
        "invite_expired" -> "招待の期限が切れています。新しい招待リンクをもらってください"
        "invite_exhausted" -> "招待の利用回数の上限に達しています"
        "invite_invalid" -> "このコミュニティでは使えない招待です"
        "join_policy_required", "join_policy_not_accepted" -> "規約が更新されました。もう一度確認してください"
        else -> "参加できませんでした（$code）"
    }
}

/** `POST /api/invites/claim` の応答。`joined` か `already_member` なら参加できた。 */
fun buzzClaimSucceeded(body: String): Boolean = runCatching {
    (protocolJson.parseToJsonElement(body).jsonObject["status"] as? JsonPrimitive)?.contentOrNull in setOf("joined", "already_member")
}.getOrDefault(false)

/** 同意の受け取り（`receipt`）。 */
fun buzzPolicyReceipt(body: String): String? = runCatching {
    (protocolJson.parseToJsonElement(body).jsonObject["receipt"] as? JsonPrimitive)?.contentOrNull
}.getOrNull()

/** NIP-98 のタグ。本文があれば SHA-256 を `payload` に入れる。 */
fun nip98Tags(url: String, method: String, body: String?): List<List<String>> = buildList {
    add(listOf("u", url))
    add(listOf("method", method))
    if (body != null) add(listOf("payload", sha256(body.encodeToByteArray()).toHex()))
}

/** `Authorization: Nostr <base64(イベントの JSON)>`（NIP-98・Blossom 共通）。 */
fun nostrAuthorizationHeader(event: NostrEvent): String =
    "Nostr " + Base64.encode(protocolJson.encodeToString(NostrEvent.serializer(), event).encodeToByteArray())

/**
 * Blossom の読み取り証明。`x` を付けずに `server` で範囲を決めるので、期限まで同じホストの画像すべてに使える。
 * Buzz は NIP-FI が有効なとき、期限を作成から60秒以内に制限する。
 */
fun blossomGetTags(host: String, now: Long, ttlSeconds: Long): List<List<String>> = listOf(
    listOf("t", "get"),
    listOf("expiration", (now + ttlSeconds).toString()),
    listOf("server", host),
)

/** ホストごとの Blossom の読み取り証明。期限の少し前に作り直す。 */
class BlossomTokenCache(private val refreshMarginSeconds: Long = 5) {
    private data class Entry(val header: String, val expiresAt: Long)

    private val lock = SynchronousLock()
    private val entries = mutableMapOf<String, Entry>()

    fun get(host: String, now: Long): String? = lock.withLock {
        entries[host]?.takeIf { now < it.expiresAt - refreshMarginSeconds }?.header
    }

    fun put(host: String, header: String, expiresAt: Long) = lock.withLock {
        entries[host] = Entry(header, expiresAt)
    }

    fun clear() = lock.withLock { entries.clear() }
}

/** 参加中の Buzz コミュニティの `/media/` を指す URL なら、そのホスト。認証が要る。 */
fun buzzMediaHost(url: String, communityRelays: Set<String>): String? {
    if (!url.startsWith("https://", ignoreCase = true)) return null
    val rest = url.substring("https://".length)
    val host = rest.substringBefore('/').lowercase()
    val path = rest.substringAfter('/', missingDelimiterValue = "")
    if (!path.startsWith("media/")) return null
    return host.takeIf { "wss://$host" in communityRelays }
}

/** Blossom のアップロードの証明。アップロードするバイト列の SHA-256 に限る。 */
fun blossomUploadTags(host: String, sha256Hex: String, now: Long, ttlSeconds: Long): List<List<String>> = listOf(
    listOf("t", "upload"),
    listOf("x", sha256Hex),
    listOf("expiration", (now + ttlSeconds).toString()),
    listOf("server", host),
)

/** Buzz の `/upload` の応答（BUD-02 の BlobDescriptor）。 */
data class BuzzBlob(val url: String, val sha256: String, val size: Long, val type: String, val dim: String?)

/** 応答を読む。URL がコミュニティの外を指すものは受け付けない（Buzz の `validateUploadResult` と同じ考え方）。 */
fun parseBuzzBlob(body: String, origin: String): BuzzBlob? = runCatching {
    val obj = protocolJson.parseToJsonElement(body).jsonObject
    fun text(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val url = text("url") ?: return null
    if (!url.startsWith("$origin/")) return null
    BuzzBlob(
        url = url,
        sha256 = text("sha256") ?: return null,
        size = (obj["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null,
        type = text("type") ?: return null,
        dim = text("dim"),
    )
}.getOrNull()

/** 本文に足す添付（Markdown か URL）と `imeta` タグ。 */
data class MessageAttachment(
    val markdown: String,
    val imeta: List<String>,
    /** 送る前の入力欄に出す縮小画像(端末の中だけで使い、送らない)。 */
    val previewBytes: ByteArray? = null,
)

/** Buzz の `attachmentMarkdown` と同じ形。画像と mp4 は `![]`、それ以外はリンク。 */
fun buzzAttachment(blob: BuzzBlob, filename: String): MessageAttachment {
    val label = filename.map { if (it.code < 32) ' ' else it }.joinToString("").replace(Regex("[\\\\`*_{}\\[\\]()!<>&]")) { "\\" + it.value }
    val inline = blob.type in setOf("image/png", "image/jpeg", "image/gif", "image/webp", "video/mp4")
    return MessageAttachment(
        markdown = "${if (inline) "!" else ""}[$label](<${blob.url}>)",
        imeta = buildList {
            add("imeta")
            add("url ${blob.url}")
            add("m ${blob.type}")
            add("size ${blob.size}")
            add("x ${blob.sha256}")
            add("filename $filename")
            blob.dim?.let { add("dim $it") }
        },
    )
}
