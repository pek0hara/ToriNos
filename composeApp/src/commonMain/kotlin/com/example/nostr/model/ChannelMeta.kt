package com.example.nostr.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChannelMeta(
    val name: String = "",
    val about: String = "",
    val picture: String = "",
)

private val channelJson = Json { ignoreUnknownKeys = true }

/** kind:40 イベントの content から ChannelMeta をパース */
fun NostrEvent.toChannelMeta(): ChannelMeta? = try {
    channelJson.decodeFromString<ChannelMeta>(content)
} catch (_: Exception) {
    null
}
