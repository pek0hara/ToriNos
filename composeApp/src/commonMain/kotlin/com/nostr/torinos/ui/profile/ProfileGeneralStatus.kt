package com.nostr.torinos.ui.profile

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.CustomEmoji
import kotlin.time.Clock

internal const val PROFILE_STATUS_KIND = 30315
internal const val PROFILE_GENERAL_STATUS_TAG = "general"

private val customEmojiCodeRegex = Regex(""":([a-zA-Z0-9_-]+):""")

data class ProfileGeneralStatus(
    val content: String,
    val customEmojis: Map<String, String> = emptyMap(),
)

internal fun NostrEvent.toActiveGeneralStatus(): ProfileGeneralStatus? {
    if (kind != PROFILE_STATUS_KIND) return null
    val statusTag = tags.firstOrNull { it.firstOrNull() == "d" }
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?: PROFILE_GENERAL_STATUS_TAG
    if (statusTag != PROFILE_GENERAL_STATUS_TAG) return null

    val expiration = tags.firstOrNull { it.firstOrNull() == "expiration" }
        ?.getOrNull(1)
        ?.toLongOrNull()
    if (expiration != null && expiration <= Clock.System.now().epochSeconds) return null

    val body = content.trim().takeIf { it.isNotBlank() } ?: return null
    return ProfileGeneralStatus(
        content = body,
        customEmojis = tags.customEmojiMap(),
    )
}

internal fun List<List<String>>.customEmojiMap(): Map<String, String> =
    mapNotNull { tag ->
        if (tag.firstOrNull() != "emoji") return@mapNotNull null
        val shortcode = tag.getOrNull(1)?.trim()?.trim(':')?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val imageUrl = tag.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        shortcode to imageUrl
    }.toMap()

internal fun customEmojiTagsForContent(content: String, emojis: List<CustomEmoji>): List<List<String>> {
    val emojiMap = emojis.associateBy { it.shortcode }
    return customEmojiCodeRegex.findAll(content)
        .mapNotNull { match ->
            val shortcode = match.groupValues[1]
            val emoji = emojiMap[shortcode] ?: return@mapNotNull null
            listOf("emoji", emoji.shortcode, emoji.imageUrl)
        }
        .distinct()
        .toList()
}
