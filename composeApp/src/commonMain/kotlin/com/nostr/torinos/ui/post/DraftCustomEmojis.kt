package com.nostr.torinos.ui.post

import com.nostr.torinos.network.CustomEmoji
import com.nostr.torinos.ui.profile.customEmojiTagsForContent

/** Resolve newly typed codes once; retain the draft's URLs for existing codes. */
internal fun resolveDraftEmojis(
    text: String,
    retained: List<CustomEmoji>,
    registered: List<CustomEmoji>,
): List<CustomEmoji> = customEmojiTagsForContent(text, registered + retained)
    .map { CustomEmoji(it[1], it[2]) }

/** A shortcode must refer to only one image within the same event. */
internal fun uniqueDraftEmoji(
    emoji: CustomEmoji,
    retained: List<CustomEmoji>,
    text: String,
): CustomEmoji {
    val existing = retained.firstOrNull { it.shortcode == emoji.shortcode }
    if (existing == null || existing.imageUrl == emoji.imageUrl) return emoji
    retained.firstOrNull {
        it.imageUrl == emoji.imageUrl &&
            it.shortcode.startsWith("${emoji.shortcode}_") &&
            it.shortcode.removePrefix("${emoji.shortcode}_").toIntOrNull() != null
    }?.let { return it }
    var suffix = 2
    while (true) {
        val code = "${emoji.shortcode}_${suffix++}"
        val match = retained.firstOrNull { it.shortcode == code }
        if (match?.imageUrl == emoji.imageUrl) return match
        if (match == null && ":$code:" !in text) return emoji.copy(shortcode = code)
    }
}
