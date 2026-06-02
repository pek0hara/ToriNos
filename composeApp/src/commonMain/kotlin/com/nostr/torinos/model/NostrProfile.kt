package com.nostr.torinos.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class NostrProfile(
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val picture: String? = null,
    val banner: String? = null,
    val about: String? = null,
    val nip05: String? = null,
    val customEmojis: Map<String, String> = emptyMap(),
) {
    /** 表示名。display_name → name → null の優先順 */
    val bestName: String?
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
}

private val profileJson = Json { ignoreUnknownKeys = true }

/** kind:0 イベントの content から NostrProfile をパース */
fun NostrEvent.toProfile(): NostrProfile? = try {
    profileJson.decodeFromString<NostrProfile>(content).copy(
        customEmojis = tags.customEmojiMap(),
    )
} catch (_: Exception) {
    null
}

private fun List<List<String>>.customEmojiMap(): Map<String, String> =
    mapNotNull { tag ->
        if (tag.firstOrNull() != "emoji") return@mapNotNull null
        val shortcode = tag.getOrNull(1)?.trim()?.trim(':')?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val imageUrl = tag.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        shortcode to imageUrl
    }.toMap()
