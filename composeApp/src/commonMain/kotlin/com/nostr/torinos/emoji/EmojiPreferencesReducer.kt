package com.nostr.torinos.emoji

internal const val MAX_RECENT_REACTIONS = 24

/** 同一アドレスがあれば置き換え、なければ末尾に登録する。絵文字が1つもないセットは登録しない。 */
internal fun EmojiPreferences.registerSet(set: RegisteredEmojiSet): EmojiPreferences {
    val normalized = set.normalized() ?: return this
    val index = sets.indexOfFirst { it.address == normalized.address }
    val updatedSets = if (index < 0) sets + normalized else sets.toMutableList().also { it[index] = normalized }
    if (updatedSets == sets && normalized.address !in unresolvedSetAddresses) return this
    return copy(
        sets = updatedSets,
        unresolvedSetAddresses = unresolvedSetAddresses - normalized.address,
        revision = revision + 1,
    )
}

/** アドレスが一致するセットだけを外す。他のセットの絵文字には触れない。 */
internal fun EmojiPreferences.unregisterSet(address: EmojiSetAddress): EmojiPreferences {
    if (!isRegistered(address) && address !in unresolvedSetAddresses) return this
    return copy(
        sets = sets.filterNot { it.address == address },
        unresolvedSetAddresses = unresolvedSetAddresses - address,
        revision = revision + 1,
    )
}

internal fun EmojiPreferences.toggleFavorite(emoji: CustomEmoji): EmojiPreferences {
    val normalized = emoji.normalizedOrNull() ?: return this
    val updated = if (isFavorite(normalized)) {
        favorites.filterNot { it.sameEmojiAs(normalized) }
    } else {
        favorites + normalized
    }
    return copy(favorites = updated, revision = revision + 1)
}

/** 使ったリアクションを先頭へ移す。同期対象ではないため revision は変えない。 */
internal fun EmojiPreferences.recordUse(reaction: RecentReaction): EmojiPreferences {
    val normalized = reaction.normalized() ?: return this
    return copy(
        recent = (listOf(normalized) + recent.filterNot { it.key == normalized.key }).take(MAX_RECENT_REACTIONS),
    )
}

/**
 * リレーの kind 10030 から得た内容を取り込む。
 *
 * 未送信の変更（[EmojiPreferences.hasUnsyncedChanges]）があるときは端末側を優先して何もしない。
 * 取り込んだ場合は、その時点を送信済みとして扱う。
 */
internal fun EmojiPreferences.applyRemote(
    favorites: List<CustomEmoji>,
    sets: List<RegisteredEmojiSet>,
    unresolved: Set<EmojiSetAddress>,
): EmojiPreferences {
    if (hasUnsyncedChanges) return this
    val normalizedSets = sets.mapNotNull { it.normalized() }.distinctBy { it.address }
    val resolvedAddresses = normalizedSets.map { it.address }.toSet()
    // 取得できたが絵文字が空のセットも、参照は失わないよう未解決として残す。
    val emptyAddresses = sets.map { it.address }.toSet() - resolvedAddresses
    return copy(
        sets = normalizedSets,
        favorites = favorites.mapNotNull { it.normalizedOrNull() }.distinctBy { it.identity },
        unresolvedSetAddresses = (unresolved + emptyAddresses) - resolvedAddresses,
        syncedRevision = revision,
    )
}

/** [sentRevision] 時点の内容をリレーへ送信できた。 */
internal fun EmojiPreferences.markSynced(sentRevision: Long): EmojiPreferences =
    if (sentRevision <= syncedRevision) this else copy(syncedRevision = minOf(sentRevision, revision))

private fun RegisteredEmojiSet.normalized(): RegisteredEmojiSet? {
    val emojis = emojis.mapNotNull { it.normalizedOrNull() }.distinctBy { it.identity }
    if (emojis.isEmpty()) return null
    return copy(title = title.trim().ifBlank { address.identifier }, emojis = emojis)
}

private fun RecentReaction.normalized(): RecentReaction? = when (this) {
    is RecentReaction.Unicode -> value.trim().takeIf { it.isNotBlank() }?.let { RecentReaction.Unicode(it) }
    is RecentReaction.Custom -> emoji.normalizedOrNull()?.let { RecentReaction.Custom(it) }
}
