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
    @SerialName("#q") val qTags: List<String>? = null,
    @SerialName("#p") val pTags: List<String>? = null,
    @SerialName("#a") val aTags: List<String>? = null,
    @SerialName("#d") val dTags: List<String>? = null,
    @SerialName("#t") val tTags: List<String>? = null,
    val search: String? = null,
)

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
