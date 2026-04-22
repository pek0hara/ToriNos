package com.example.nostr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class NostrProfile(
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val picture: String? = null,
    val about: String? = null,
    val nip05: String? = null,
) {
    /** 表示名。display_name → name → null の優先順 */
    val bestName: String?
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
}

private val profileJson = Json { ignoreUnknownKeys = true }

/** kind:0 イベントの content から NostrProfile をパース */
fun NostrEvent.toProfile(): NostrProfile? = try {
    profileJson.decodeFromString<NostrProfile>(content)
} catch (_: Exception) {
    null
}
