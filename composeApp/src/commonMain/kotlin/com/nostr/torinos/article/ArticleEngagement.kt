package com.nostr.torinos.article

import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.incrementedWithUnicodeReaction
import com.nostr.torinos.model.toReactionOption

private const val REACTION_KIND = 7
private const val TEXT_NOTE_KIND = 1

/** 記事1件に付いたリアクションの集計。 */
data class ArticleReactionSummary(
    val likeCount: Int = 0,
    val customReactions: List<CustomReaction> = emptyList(),
    val unicodeReactions: List<UnicodeReaction> = emptyList(),
    val ownLikeEventId: String? = null,
    val ownEmojiReactionEventIds: Map<String, String> = emptyMap(),
) {
    val totalCount: Int
        get() = likeCount + customReactions.sumOf { it.count } + unicodeReactions.sumOf { it.count }
}

/**
 * 記事へのリアクションとコメントを取得するフィルター。記事は置換可能イベントのため、
 * 版のIDではなくaddressで引く。版IDを`e`タグだけで参照するリアクションも補助的に拾う。
 */
internal fun articleEngagementFilters(
    address: String,
    articleEventIds: Collection<String>,
    limit: Int,
): List<NostrFilter> = listOf(
    NostrFilter(kinds = listOf(REACTION_KIND), aTags = listOf(address), limit = limit),
    NostrFilter(kinds = listOf(REACTION_KIND), eTags = articleEventIds.distinct(), limit = limit),
    NostrFilter(kinds = listOf(COMMENT_EVENT_KIND), rootAddressTags = listOf(address), limit = limit),
    NostrFilter(kinds = listOf(TEXT_NOTE_KIND), aTags = listOf(address), limit = limit),
)

/** [event]が記事（address、またはいずれかの版ID）へのリアクションか。 */
internal fun NostrEvent.isReactionToArticle(address: String, articleEventIds: Set<String>): Boolean {
    if (kind != REACTION_KIND) return false
    if (tags.any { it.firstOrNull() == "a" && it.getOrNull(1) == address }) return true
    val target = tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
    return target != null && target in articleEventIds
}

/**
 * 記事へのリアクションを集計する。同じイベントIDは1回だけ数える。
 * 本文が`+`または空はいいね、`-`は数えず、絵文字は種類ごとに数える。
 */
internal fun summarizeArticleReactions(
    events: Collection<NostrEvent>,
    address: String,
    articleEventIds: Set<String>,
    ownPubkey: String?,
    isMuted: (String) -> Boolean = { false },
): ArticleReactionSummary {
    var summary = ArticleReactionSummary()
    events
        .asSequence()
        .distinctBy { it.id }
        .filter { it.isReactionToArticle(address, articleEventIds) }
        .filterNot { isMuted(it.pubkey) }
        .sortedBy { it.createdAt }
        .forEach { event ->
            val isOwn = ownPubkey != null && event.pubkey == ownPubkey
            val content = event.content.trim()
            val option = event.toReactionOption()
            summary = when {
                content == "+" || content.isEmpty() -> summary.copy(
                    likeCount = summary.likeCount + 1,
                    ownLikeEventId = if (isOwn) event.id else summary.ownLikeEventId,
                )
                option is ReactionOption.Custom -> summary.copy(
                    customReactions = summary.customReactions.incrementedWith(
                        CustomReaction(option.shortcode, option.imageUrl),
                    ),
                    ownEmojiReactionEventIds = summary.ownEmojiReactionEventIds.withOwn(isOwn, option, event.id),
                )
                option is ReactionOption.Unicode -> summary.copy(
                    unicodeReactions = summary.unicodeReactions.incrementedWithUnicodeReaction(
                        UnicodeReaction(option.value),
                    ),
                    ownEmojiReactionEventIds = summary.ownEmojiReactionEventIds.withOwn(isOwn, option, event.id),
                )
                else -> summary
            }
        }
    return summary
}

private fun Map<String, String>.withOwn(isOwn: Boolean, option: ReactionOption, eventId: String): Map<String, String> =
    if (isOwn) this + (option.key to eventId) else this

/**
 * 記事へ直接付いたコメントか。
 * - kind 1111: ルートが記事address（`A`）で、親の種類（`k`）が記事のもの。コメントへの返信は含めない。
 * - kind 1: 記事addressの`a`タグを持つ旧形式の返信。`a`タグのマーカーが`mention`のもの、
 *   記事以外のイベントへ返信しているものは含めない。
 */
internal fun NostrEvent.isTopLevelArticleComment(address: String, articleEventIds: Set<String>): Boolean =
    when (kind) {
        COMMENT_EVENT_KIND ->
            tags.any { it.firstOrNull() == "A" && it.getOrNull(1) == address } &&
                tags.firstOrNull { it.firstOrNull() == "k" }?.getOrNull(1) == NIP23_ARTICLE_KIND.toString()
        TEXT_NOTE_KIND -> {
            val addressTag = tags.firstOrNull { it.firstOrNull() == "a" && it.getOrNull(1) == address }
            addressTag != null &&
                addressTag.getOrNull(3) != "mention" &&
                tags
                    .filter { it.firstOrNull() == "e" && it.getOrNull(3) != "mention" }
                    .all { it.getOrNull(1) in articleEventIds }
        }
        else -> false
    }

/** 記事へ直接付いたコメントを古い順に返す。 */
internal fun articleTopLevelComments(
    events: Collection<NostrEvent>,
    address: String,
    articleEventIds: Set<String>,
    isMuted: (String) -> Boolean = { false },
): List<NostrEvent> = events
    .asSequence()
    .distinctBy { it.id }
    .filter { it.isTopLevelArticleComment(address, articleEventIds) }
    .filterNot { isMuted(it.pubkey) }
    .sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
    .toList()
