package com.nostr.torinos.emoji

import com.nostr.torinos.network.CustomEmoji

private val customEmojiCodeRegex = Regex(""":([a-zA-Z0-9_-]+):""")

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
