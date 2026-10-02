package com.nostr.torinos.emoji

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter

/** 置換可能イベントの `d` タグ。 */
internal fun NostrEvent.dTag(): String? = tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)

/** kind 30030 のイベントを登録用のセットにする。絵文字が無ければ null。 */
internal fun NostrEvent.toRegisteredEmojiSet(): RegisteredEmojiSet? {
    if (kind != EmojiSetAddress.KIND_EMOJI_SET) return null
    val identifier = dTag() ?: return null
    val address = EmojiSetAddress.of(pubkey, identifier) ?: return null
    val title = tags.firstOrNull { it.firstOrNull() == "title" }?.getOrNull(1)?.trim()
        ?.takeIf { it.isNotBlank() } ?: identifier
    val emojis = tags.emojiTags().distinctBy { it.identity }
    if (emojis.isEmpty()) return null
    return RegisteredEmojiSet(address, title, emojis)
}

internal fun NostrEvent.toPublishedEmojiSet(): PublishedEmojiSet? {
    val set = toRegisteredEmojiSet() ?: return null
    return PublishedEmojiSet(
        address = set.address,
        sourceEventId = id,
        name = set.title,
        createdAt = createdAt,
        emojis = set.emojis,
    )
}

/** このアドレスのセット1件だけを取る条件。 */
internal fun EmojiSetAddress.toFilter(): NostrFilter = NostrFilter(
    kinds = listOf(EmojiSetAddress.KIND_EMOJI_SET),
    authors = listOf(author),
    dTags = listOf(identifier),
    limit = 1,
)
