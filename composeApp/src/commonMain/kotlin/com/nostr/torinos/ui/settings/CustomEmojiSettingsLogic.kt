package com.nostr.torinos.ui.settings

import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.emoji.PublishedEmojiSet
import com.nostr.torinos.emoji.RegisteredEmojiSet
import com.nostr.torinos.emoji.normalizeShortcode

/** 設定画面で詳細を開く絵文字セット。登録済みか公開一覧のどちらから来たかは問わない。 */
internal data class EmojiSetView(
    val address: EmojiSetAddress,
    val title: String,
    val emojis: List<CustomEmoji>,
)

internal fun RegisteredEmojiSet.toView() = EmojiSetView(address, title, emojis)

internal fun PublishedEmojiSet.toView() = EmojiSetView(address, name, emojis)

internal fun EmojiSetView.toRegisteredSet() = RegisteredEmojiSet(address, title, emojis)

/**
 * タップした絵文字から開くセットを決める。
 *
 * 1. アドレスが分かれば、そのセット（登録済み → 公開一覧の順）。
 * 2. shortcode と URL が一致するセット（登録済み → 公開一覧の順）。
 * 3. 公開一覧の読み込みが終わっていて、shortcode だけで一致するセットがちょうど1つならそれ。
 * どれでもないか、URL が分からなければ null（検索語として一覧を表示する）。
 */
internal fun findRequestedEmojiSet(
    shortcode: String,
    imageUrl: String,
    address: EmojiSetAddress?,
    registered: List<RegisteredEmojiSet>,
    published: List<PublishedEmojiSet>,
    isPublishedLoading: Boolean,
): EmojiSetView? {
    val candidates = registered.map { it.toView() } + published.map { it.toView() }
    if (address != null) {
        candidates.firstOrNull { it.address == address }?.let { return it }
    }
    val code = normalizeShortcode(shortcode)
    val url = imageUrl.trim()
    // 画像の分からない未登録コード（本文中の :xxx:）は、検索語として一覧を出すだけにする。
    if (code.isBlank() || url.isBlank()) return null
    candidates.firstOrNull { set -> set.emojis.any { it.shortcode == code && it.imageUrl == url } }
        ?.let { return it }
    if (isPublishedLoading) return null
    return candidates
        .filter { set -> set.emojis.any { it.shortcode == code } }
        .distinctBy { it.address }
        .singleOrNull()
}

internal fun selectInitialEmoji(
    emojis: List<CustomEmoji>,
    initialShortcode: String,
    initialImageUrl: String,
): CustomEmoji? {
    val shortcode = normalizeShortcode(initialShortcode)
    val imageUrl = initialImageUrl.trim()
    return emojis.firstOrNull { emoji ->
        emoji.shortcode == shortcode && emoji.imageUrl == imageUrl
    } ?: emojis.firstOrNull { emoji ->
        emoji.shortcode == shortcode
    } ?: emojis.firstOrNull()
}

internal fun List<PublishedEmojiSet>.filterPublishedSets(
    query: String,
    registeredOnly: Boolean,
    isRegistered: (EmojiSetAddress) -> Boolean,
): List<PublishedEmojiSet> {
    val normalizedQuery = query.trim().lowercase()
    return filter { set ->
        (!registeredOnly || isRegistered(set.address)) &&
            (
                normalizedQuery.isBlank() ||
                    set.name.lowercase().contains(normalizedQuery) ||
                    set.authorPubkey.contains(normalizedQuery) ||
                    set.emojis.any { it.shortcode.lowercase().contains(normalizedQuery) }
                )
    }
}

internal fun List<RegisteredEmojiSet>.filterRegisteredSets(query: String): List<RegisteredEmojiSet> {
    val normalizedQuery = query.trim().lowercase()
    val sorted = sortedBy { it.title.lowercase() }
    if (normalizedQuery.isBlank()) return sorted
    return sorted.filter { set ->
        set.title.lowercase().contains(normalizedQuery) ||
            set.emojis.any { emoji ->
                emoji.shortcode.lowercase().contains(normalizedQuery) ||
                    emoji.imageUrl.lowercase().contains(normalizedQuery)
            }
    }
}
