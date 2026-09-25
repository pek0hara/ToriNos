package com.nostr.torinos.status

import com.nostr.torinos.model.NostrEvent

const val STATUS_EVENT_KIND = 30315
const val GENERAL_STATUS_IDENTIFIER = "general"
const val MUSIC_STATUS_IDENTIFIER = "music"

data class StatusAddress(
    val pubkey: String,
    val identifier: String,
)

data class StatusEntry(
    val event: NostrEvent,
    val address: StatusAddress,
    val content: String,
    val expiration: Long?,
    val referenceUrls: List<String>,
    val customEmojis: Map<String, String>,
) {
    val identifier: String get() = address.identifier
    val key: String get() = "${address.pubkey}:${address.identifier}"
}

fun isNewerStatusEvent(candidate: NostrEvent, current: NostrEvent): Boolean =
    candidate.createdAt > current.createdAt ||
        (candidate.createdAt == current.createdAt && candidate.id < current.id)
