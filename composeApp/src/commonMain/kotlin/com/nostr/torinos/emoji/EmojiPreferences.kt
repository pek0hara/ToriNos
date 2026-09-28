package com.nostr.torinos.emoji

import com.nostr.torinos.network.CustomEmoji

/** 同一性は (shortcode, imageUrl)。 */
internal val CustomEmoji.identity: Pair<String, String> get() = normalizeShortcode(shortcode) to imageUrl.trim()

internal fun CustomEmoji.sameEmojiAs(other: CustomEmoji): Boolean = identity == other.identity

internal fun CustomEmoji.normalizedOrNull(): CustomEmoji? {
    val shortcode = normalizeShortcode(shortcode)
    val imageUrl = imageUrl.trim()
    return if (shortcode.isBlank() || imageUrl.isBlank()) null else CustomEmoji(shortcode, imageUrl)
}

/** 登録済みの kind 30030 絵文字セット。 */
data class RegisteredEmojiSet(
    val address: EmojiSetAddress,
    val title: String,
    val emojis: List<CustomEmoji>,
)

/** 最近使ったリアクション。端末内だけに保存し、同期しない（決定事項 D4）。 */
sealed interface RecentReaction {
    val key: String

    data class Unicode(val value: String) : RecentReaction {
        override val key: String get() = "unicode:$value"
    }

    data class Custom(val emoji: CustomEmoji) : RecentReaction {
        override val key: String get() = "custom:${emoji.shortcode}:${emoji.imageUrl}"
    }
}

/**
 * アカウントごとのカスタム絵文字設定。
 *
 * [revision] は端末で同期対象（セット・お気に入り）を変更するたびに増え、[syncedRevision] はリレーへ
 * 送信済み、またはリレーの内容を取り込んだ時点の値。`revision > syncedRevision` なら未送信の変更がある。
 */
data class EmojiPreferences(
    val sets: List<RegisteredEmojiSet> = emptyList(),
    val favorites: List<CustomEmoji> = emptyList(),
    val unresolvedSetAddresses: Set<EmojiSetAddress> = emptySet(),
    val recent: List<RecentReaction> = emptyList(),
    val revision: Long = 0,
    val syncedRevision: Long = 0,
) {
    /** 使える絵文字。優先順（お気に入り → セットの登録順）で、(shortcode, imageUrl) の重複を除く。 */
    val available: List<CustomEmoji> by lazy {
        (favorites + sets.flatMap { it.emojis }).distinctBy { it.identity }
    }

    val hasUnsyncedChanges: Boolean get() = revision > syncedRevision

    /** shortcode の既定解決。同じ shortcode が複数あれば [available] の先頭を使う。 */
    fun resolve(shortcode: String): CustomEmoji? {
        val normalized = normalizeShortcode(shortcode)
        return available.firstOrNull { it.shortcode == normalized }
    }

    fun isRegistered(address: EmojiSetAddress): Boolean = sets.any { it.address == address }

    fun isFavorite(emoji: CustomEmoji): Boolean = favorites.any { it.sameEmojiAs(emoji) }
}
