package com.nostr.torinos.model

data class CustomReaction(
    val shortcode: String,
    val imageUrl: String,
    val count: Int = 1,
)

fun NostrEvent.toCustomReaction(): CustomReaction? {
    if (kind != 7) return null
    val shortcode = content.trim().removeSurrounding(":").takeIf {
        content.trim() == ":$it:" && it.isNotBlank()
    } ?: return null
    val emojiTag = tags.firstOrNull { tag ->
        tag.firstOrNull() == "emoji" &&
            tag.getOrNull(1)?.trim()?.trim(':') == shortcode &&
            !tag.getOrNull(2).isNullOrBlank()
    } ?: return null
    return CustomReaction(
        shortcode = shortcode,
        imageUrl = emojiTag[2].trim(),
    )
}

fun List<CustomReaction>.incrementedWith(reaction: CustomReaction): List<CustomReaction> {
    val existingIndex = indexOfFirst {
        it.shortcode == reaction.shortcode && it.imageUrl == reaction.imageUrl
    }
    if (existingIndex < 0) return this + reaction
    return mapIndexed { index, current ->
        if (index == existingIndex) current.copy(count = current.count + reaction.count) else current
    }
}
