package com.nostr.torinos.ui.status

import com.nostr.torinos.status.GENERAL_STATUS_IDENTIFIER
import com.nostr.torinos.status.MUSIC_STATUS_IDENTIFIER
import com.nostr.torinos.status.StatusEntry
import com.nostr.torinos.ui.components.formatTimestamp

/** 一覧の1行。同じ人の general / music などをまとめて「その人の今」として見せる。 */
internal data class StatusUserGroup(
    val pubkey: String,
    val statuses: List<StatusEntry>,
) {
    val latestCreatedAt: Long get() = statuses.maxOf { it.event.createdAt }
}

/**
 * 新しい順に並んだステータスを人ごとにまとめる。人の並びは各人の最新の更新順を保つ。
 * 自分のステータスは先頭の固定カードで見せるので一覧からは外す。
 */
internal fun groupStatusesByUser(
    statuses: List<StatusEntry>,
    excludePubkey: String?,
): List<StatusUserGroup> = statuses
    .asSequence()
    .filter { it.event.pubkey != excludePubkey }
    .groupBy { it.event.pubkey }
    .map { (pubkey, entries) -> StatusUserGroup(pubkey, entries.sortedForDisplay()) }
    .sortedByDescending { it.latestCreatedAt }

/** general → music → その他（新しい順）の順に並べる。 */
internal fun Collection<StatusEntry>.sortedForDisplay(): List<StatusEntry> =
    sortedWith(
        compareBy<StatusEntry> { statusCategoryOrder(it.identifier) }
            .thenByDescending { it.event.createdAt },
    )

private fun statusCategoryOrder(identifier: String): Int = when (identifier) {
    GENERAL_STATUS_IDENTIFIER -> 0
    MUSIC_STATUS_IDENTIFIER -> 1
    else -> 2
}

/** フィルターや行の頭に出すタグ名。UUID のような長い識別子は先頭だけにする。 */
internal fun customStatusTagLabel(identifier: String): String =
    if (identifier.length > CUSTOM_TAG_MAX_LENGTH) {
        identifier.take(CUSTOM_TAG_SHORT_LENGTH) + "…"
    } else {
        identifier
    }

/** ステータスは「今」を表すので、相対時刻で見せる。1週間を超えたら日時にする。 */
internal fun statusRelativeTimeText(createdAt: Long, now: Long): String {
    val elapsed = (now - createdAt).coerceAtLeast(0L)
    return when {
        elapsed < 60L -> "たった今"
        elapsed < 3600L -> "${elapsed / 60L}分前"
        elapsed < 86400L -> "${elapsed / 3600L}時間前"
        elapsed < 7L * 86400L -> "${elapsed / 86400L}日前"
        else -> formatTimestamp(createdAt, nowEpochSeconds = now)
    }
}

internal fun isStaleStatus(createdAt: Long, now: Long): Boolean =
    now - createdAt >= STALE_STATUS_SECONDS

/** 参照URLはドメインだけ見せる。 */
internal fun referenceUrlDisplayHost(url: String): String {
    val withoutScheme = url.substringAfter("://", url)
    val host = withoutScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .removePrefix("www.")
    return host.ifEmpty { url }
}

private const val CUSTOM_TAG_MAX_LENGTH = 12
private const val CUSTOM_TAG_SHORT_LENGTH = 8
private const val STALE_STATUS_SECONDS = 86400L
