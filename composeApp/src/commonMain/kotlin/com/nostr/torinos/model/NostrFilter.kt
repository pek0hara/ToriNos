package com.nostr.torinos.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NostrFilter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    @SerialName("#e") val eTags: List<String>? = null,
    @SerialName("#E") val rootEventTags: List<String>? = null,
    @SerialName("#k") val parentKindTags: List<String>? = null,
    @SerialName("#K") val rootKindTags: List<String>? = null,
    @SerialName("#q") val qTags: List<String>? = null,
    @SerialName("#p") val pTags: List<String>? = null,
    @SerialName("#P") val rootPubkeyTags: List<String>? = null,
    @SerialName("#a") val aTags: List<String>? = null,
    @SerialName("#A") val rootAddressTags: List<String>? = null,
    @SerialName("#d") val dTags: List<String>? = null,
    @SerialName("#t") val tTags: List<String>? = null,
    val search: String? = null,
)

/** イベントがフィルターの条件を満たすか（NIP-01）。search はリレー側の解釈に任せ、判定しない。 */
fun NostrFilter.matches(event: NostrEvent): Boolean {
    if (ids != null && event.id !in ids) return false
    if (authors != null && event.pubkey !in authors) return false
    if (kinds != null && event.kind !in kinds) return false
    if (since != null && event.createdAt < since) return false
    if (until != null && event.createdAt > until) return false
    // 受信のたびに呼ばれるので、条件の一覧を組み立てず項目ごとに確かめる。
    return event.hasTagIn("e", eTags) &&
        event.hasTagIn("E", rootEventTags) &&
        event.hasTagIn("k", parentKindTags) &&
        event.hasTagIn("K", rootKindTags) &&
        event.hasTagIn("q", qTags) &&
        event.hasTagIn("p", pTags) &&
        event.hasTagIn("P", rootPubkeyTags) &&
        event.hasTagIn("a", aTags) &&
        event.hasTagIn("A", rootAddressTags) &&
        event.hasTagIn("d", dTags) &&
        event.hasTagIn("t", tTags)
}

private fun NostrEvent.hasTagIn(name: String, values: List<String>?): Boolean =
    values == null || tags.any { tag -> tag.getOrNull(0) == name && tag.getOrNull(1) in values }

/**
 * [event] が当てはまる最初のフィルターの添字。リレーの照合の癖（タグの大文字小文字や前方一致など）で
 * どれにも厳密に当てはまらないときは、種類だけで判定する。それでもなければ null。
 */
fun List<NostrFilter>.indexOfMatching(event: NostrEvent): Int? = indexOfMatching(event) { it }

/** [filterOf] で各要素のフィルターを取り出して [indexOfMatching] と同じ判定をする。 */
inline fun <T> List<T>.indexOfMatching(event: NostrEvent, filterOf: (T) -> NostrFilter): Int? =
    indexOfFirst { filterOf(it).matches(event) }.takeIf { it >= 0 }
        ?: indexOfFirst { item -> filterOf(item).kinds?.let { event.kind in it } ?: true }.takeIf { it >= 0 }

private val filterJson = Json { encodeDefaults = false }

fun buildReqMessage(subscriptionId: String, filter: NostrFilter): String =
    buildReqMessage(subscriptionId, listOf(filter))

fun buildReqMessage(subscriptionId: String, filters: List<NostrFilter>): String {
    require(filters.isNotEmpty()) { "REQには1件以上のフィルターが必要です" }
    val encodedFilters = filters.joinToString(",") { filterJson.encodeToString(it) }
    return """["REQ","$subscriptionId",$encodedFilters]"""
}

fun buildCloseMessage(subscriptionId: String): String =
    """["CLOSE","$subscriptionId"]"""
