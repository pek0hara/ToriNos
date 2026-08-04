package com.nostr.torinos.model

data class CustomReaction(
    val shortcode: String,
    val imageUrl: String,
    val count: Int = 1,
)

data class UnicodeReaction(
    val content: String,
    val count: Int = 1,
)

sealed interface ReactionOption {
    val key: String
    val eventContent: String

    data class Unicode(
        val value: String,
    ) : ReactionOption {
        override val key: String = unicodeReactionKey(value)
        override val eventContent: String = value
    }

    data class Custom(
        val shortcode: String,
        val imageUrl: String,
    ) : ReactionOption {
        override val key: String = customReactionKey(shortcode, imageUrl)
        override val eventContent: String = ":${shortcode.trim().trim(':')}:"
    }
}

fun unicodeReactionKey(value: String): String = "unicode:${value.trim()}"

fun customReactionKey(shortcode: String, imageUrl: String): String =
    "custom:${shortcode.trim().trim(':')}:${imageUrl.trim()}"

fun ReactionOption.eventTags(eventId: String, eventPubkey: String): List<List<String>> = buildList {
    add(listOf("e", eventId))
    add(listOf("p", eventPubkey))
    if (this@eventTags is ReactionOption.Custom) {
        add(listOf("emoji", shortcode.trim().trim(':'), imageUrl.trim()))
    }
}

fun NostrEvent.toReactionOption(): ReactionOption? =
    toCustomReaction()?.let { ReactionOption.Custom(it.shortcode, it.imageUrl) }
        ?: toUnicodeReaction()?.let { ReactionOption.Unicode(it.content) }

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

fun List<CustomReaction>.decrementedWith(reaction: ReactionOption.Custom): List<CustomReaction> =
    mapNotNull { current ->
        if (
            current.shortcode == reaction.shortcode.trim().trim(':') &&
            current.imageUrl == reaction.imageUrl.trim()
        ) {
            current.copy(count = current.count - 1).takeIf { it.count > 0 }
        } else {
            current
        }
    }

fun NostrEvent.toUnicodeReaction(): UnicodeReaction? {
    if (kind != 7) return null
    val value = content.trim()
    if (toCustomReaction() != null) return null
    if (!value.isUnicodeEmojiReactionContent()) return null
    return UnicodeReaction(value)
}

fun String.isUnicodeEmojiReactionContent(): Boolean {
    val value = trim()
    if (value.isBlank() || value == "+" || value == "-") return false
    if (value.length >= 3 && value.startsWith(":") && value.endsWith(":")) return false
    return value.containsEmojiCodePoint()
}

fun List<UnicodeReaction>.incrementedWithUnicodeReaction(
    reaction: UnicodeReaction,
): List<UnicodeReaction> {
    val existingIndex = indexOfFirst { it.content == reaction.content }
    if (existingIndex < 0) return this + reaction
    return mapIndexed { index, current ->
        if (index == existingIndex) current.copy(count = current.count + reaction.count) else current
    }
}

fun List<UnicodeReaction>.decrementedWithUnicodeReaction(
    reaction: ReactionOption.Unicode,
): List<UnicodeReaction> = mapNotNull { current ->
    if (current.content == reaction.value.trim()) {
        current.copy(count = current.count - 1).takeIf { it.count > 0 }
    } else {
        current
    }
}

private fun String.containsEmojiCodePoint(): Boolean {
    var index = 0
    while (index < length) {
        val first = this[index].code
        val codePoint = if (
            first in 0xD800..0xDBFF &&
            index + 1 < length &&
            this[index + 1].code in 0xDC00..0xDFFF
        ) {
            index += 1
            0x10000 + ((first - 0xD800) shl 10) + (this[index].code - 0xDC00)
        } else {
            first
        }
        if (
            codePoint == 0x00A9 ||
            codePoint == 0x00AE ||
            codePoint == 0x203C ||
            codePoint == 0x2049 ||
            codePoint == 0x20E3 ||
            codePoint in 0x2100..0x27FF ||
            codePoint in 0x2B00..0x2BFF ||
            codePoint in 0x1F000..0x1FAFF
        ) {
            return true
        }
        index += 1
    }
    return false
}
