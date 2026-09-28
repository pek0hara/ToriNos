package com.nostr.torinos.ui.post

import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.customEmojiTagsForContent
import com.nostr.torinos.model.ReactionOption

/** Resolve newly typed codes once; retain the draft's URLs for existing codes. */
internal fun resolveDraftEmojis(
    text: String,
    retained: List<CustomEmoji>,
    registered: List<CustomEmoji>,
): List<CustomEmoji> = customEmojiTagsForContent(text, retained + registered)
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

/** 下書きへの絵文字挿入の結果。[customEmoji] は下書きに保持するカスタム絵文字（Unicode なら null）。 */
internal data class DraftEmojiInsertion(
    val text: String,
    val cursor: Int,
    val customEmoji: CustomEmoji?,
)

/**
 * 選択範囲を絵文字で置き換える。カスタム絵文字は、下書き内で同じ shortcode が別の画像を指さないよう
 * [uniqueDraftEmoji] で shortcode を決める。[maxLength] を超える場合は null。
 * 投稿画面とチャンネルの入力欄で共通に使う。
 */
internal fun insertDraftEmoji(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    option: ReactionOption,
    retained: List<CustomEmoji>,
    maxLength: Int = Int.MAX_VALUE,
): DraftEmojiInsertion? {
    val customEmoji = (option as? ReactionOption.Custom)?.let {
        uniqueDraftEmoji(CustomEmoji(it.shortcode, it.imageUrl), retained, text)
    }
    val insertion = customEmoji?.let { ":${it.shortcode}:" } ?: option.eventContent
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val newText = text.replaceRange(start, end, insertion)
    if (newText.length > maxLength) return null
    return DraftEmojiInsertion(newText, start + insertion.length, customEmoji)
}
