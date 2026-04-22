package com.example.nostr.model

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
    @SerialName("#t") val tTags: List<String>? = null,
    val search: String? = null,
)

private val filterJson = Json { encodeDefaults = false }

fun buildReqMessage(subscriptionId: String, filter: NostrFilter): String =
    """["REQ","$subscriptionId",${filterJson.encodeToString(filter)}]"""

fun buildCloseMessage(subscriptionId: String): String =
    """["CLOSE","$subscriptionId"]"""
