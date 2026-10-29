package com.nostr.torinos.zap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** LNURL-pay（LUD-06）の応答のうち、Zap に使う部分。額はすべて msats。 */
data class PayEndpoint(
    /** 応答を取りに行った URL。 */
    val url: String,
    val callback: String,
    val minSendableMsats: Long,
    val maxSendableMsats: Long,
    /** LUD-12 のコメントの上限。0 ならコメントを付けられない。 */
    val commentAllowed: Int = 0,
    /** Zap に対応していれば、レシートに署名する公開鍵。対応していなければ null。 */
    val nostrPubkey: String? = null,
) {
    val allowsNostr: Boolean get() = nostrPubkey != null
    val lnurl: String get() = encodeLnurl(url)

    fun accepts(amountMsats: Long): Boolean = amountMsats in minSendableMsats..maxSendableMsats
}

/** 受け取り先とのやり取りの失敗。画面にはメッセージをそのまま出す。 */
class ZapException(message: String) : Exception(message)

internal const val UNREACHABLE_MESSAGE = "受け取り先に接続できません"
internal const val INVALID_ENDPOINT_MESSAGE = "受け取り先の設定が正しくありません"
private val HEX64 = Regex("[0-9a-f]{64}")
private val lnurlJson = Json { ignoreUnknownKeys = true; isLenient = false }

internal fun parseJsonObject(body: String): JsonObject =
    runCatching { lnurlJson.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: throw ZapException(UNREACHABLE_MESSAGE)

/** `status: "ERROR"` の応答なら、その理由で失敗にする。 */
internal fun JsonObject.throwIfError() {
    if ((this["status"] as? JsonPrimitive)?.contentOrNull?.uppercase() == "ERROR") {
        val reason = (this["reason"] as? JsonPrimitive)?.contentOrNull?.take(200)
        throw ZapException(reason?.let { "受け取り先がエラーを返しました: $it" } ?: "受け取り先がエラーを返しました")
    }
}

/** LNURL-pay の応答を読み、§7.2 の条件を確かめる。満たさなければ [ZapException]。 */
fun parsePayEndpoint(url: String, body: String): PayEndpoint {
    val json = parseJsonObject(body)
    json.throwIfError()
    fun string(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
    fun long(key: String) = (json[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    if (string("tag") != "payRequest") throw ZapException(INVALID_ENDPOINT_MESSAGE)
    val callback = string("callback")?.takeIf { it.startsWith("https://") } ?: throw ZapException(INVALID_ENDPOINT_MESSAGE)
    val min = long("minSendable")
    val max = long("maxSendable")
    if (min == null || max == null || min <= 0 || max < min) throw ZapException(INVALID_ENDPOINT_MESSAGE)
    val allowsNostr = (json["allowsNostr"] as? JsonPrimitive)?.booleanOrNull == true
    val nostrPubkey = string("nostrPubkey")?.takeIf { allowsNostr && it.matches(HEX64) }
    val commentAllowed = long("commentAllowed")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
    return PayEndpoint(url, callback, min, max, commentAllowed, nostrPubkey)
}

/** callback の応答から請求書を取り出す。 */
fun parseInvoiceResponse(body: String): String {
    val json = parseJsonObject(body)
    json.throwIfError()
    return (json["pr"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw ZapException("請求書を受け取れませんでした")
}
