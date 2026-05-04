package com.nostr.torinos.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    val shortPubkey: String get() = pubkey.take(8) + "…" + pubkey.takeLast(8)
    val clientName: String? get() = tags.firstOrNull { it.firstOrNull() == "client" }?.getOrNull(1)
}
