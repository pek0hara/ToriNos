package com.nostr.torinos.ui.notification

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.isFullEventId
import kotlinx.serialization.Serializable

/**
 * 引用リポストを `#q` で探すための、自分の最近の投稿。
 * 引用に `p` タグを付けないクライアントがあり（NIP-18 は `q` タグだけを求める）、`#p` の購読では届かないため。
 */
@Serializable
data class OwnPostRef(val id: String, val createdAt: Long)

internal const val OWN_RECENT_POST_LIMIT = 100

/** 自分の投稿だけを足し、新しい順に [limit] 件まで残す。 */
internal fun mergeOwnRecentPosts(
    current: List<OwnPostRef>,
    fetched: Collection<NostrEvent>,
    ownPubkey: String,
    limit: Int = OWN_RECENT_POST_LIMIT,
): List<OwnPostRef> {
    val added = fetched.filter { it.pubkey == ownPubkey && it.kind == 1 && isFullEventId(it.id) }
        .map { OwnPostRef(it.id, it.createdAt) }
    return (current + added).distinctBy { it.id }
        .sortedWith(compareByDescending<OwnPostRef> { it.createdAt }.thenBy { it.id })
        .take(limit)
}

internal fun ownRecentPostsFilter(ownPubkey: String, limit: Int = OWN_RECENT_POST_LIMIT): NostrFilter =
    NostrFilter(authors = listOf(ownPubkey), kinds = listOf(1), limit = limit)

/** 自分の投稿を引用した kind 1。対象がなければ null。 */
internal fun quoteFilter(ownPostIds: Collection<String>, limit: Int): NostrFilter? =
    ownPostIds.takeIf { it.isNotEmpty() }?.let { NostrFilter(kinds = listOf(1), qTags = it.toList(), limit = limit) }
