package com.nostr.torinos.emoji

internal const val EMOJI_TAG = "emoji"

/** `["emoji", shortcode, url, <set-address>?]` を読む。shortcode か URL が空なら null。 */
internal fun parseEmojiTag(tag: List<String>): CustomEmoji? {
    if (tag.firstOrNull() != EMOJI_TAG) return null
    val shortcode = tag.getOrNull(1)?.let(::normalizeShortcode)?.takeIf { it.isNotBlank() } ?: return null
    val imageUrl = tag.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return CustomEmoji(shortcode, imageUrl)
}

/** 有効な `emoji` タグをタグ順に返す。重複は取り除かない。 */
internal fun List<List<String>>.emojiTags(): List<CustomEmoji> = mapNotNull(::parseEmojiTag)

/** shortcode → URL。同じ shortcode が複数あれば後のタグを使う。 */
internal fun List<List<String>>.customEmojiMap(): Map<String, String> =
    emojiTags().associate { it.shortcode to it.imageUrl }

internal fun CustomEmoji.toEmojiTag(): List<String> =
    listOf(EMOJI_TAG, normalizeShortcode(shortcode), imageUrl.trim())

/**
 * 本文に現れる `:shortcode:` のうち、[emojis] にあるものだけを出現順・重複なしでタグにする。
 * 同じ shortcode が複数あれば [emojis] の先に並ぶものを使うので、呼び出し側は優先順に渡す。
 */
internal fun customEmojiTagsForContent(content: String, emojis: List<CustomEmoji>): List<List<String>> {
    val emojiMap = LinkedHashMap<String, CustomEmoji>()
    emojis.forEach { emoji -> emojiMap.getOrPut(normalizeShortcode(emoji.shortcode)) { emoji } }
    return CustomEmojiCodeRegex.findAll(content)
        .mapNotNull { match -> emojiMap[match.groupValues[1]]?.toEmojiTag() }
        .distinct()
        .toList()
}
