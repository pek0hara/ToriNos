package com.nostr.torinos.article

import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.engagement.displayOwnEmojiReactionEventIds
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.util.BoundedLruCache
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 記事一覧のカードに出す、リアクションとコメントの件数。 */
data class ArticleEngagementSummary(
    val reactionCount: Int,
    val commentCount: Int,
    /** 取得件数の上限に達し、実際はこれ以上ある可能性がある。 */
    val isReactionLowerBound: Boolean = false,
    val isCommentLowerBound: Boolean = false,
    val ownReaction: OwnArticleReaction? = null,
    val fetchedAtMillis: Long,
    /** 一覧の読み込み直しなどで、期限に関係なく次回取り直す。 */
    val needsRefresh: Boolean = false,
)

/** 自分が記事に付けているリアクション。 */
sealed interface OwnArticleReaction {
    data object Like : OwnArticleReaction
    data class Emoji(val option: ReactionOption) : OwnArticleReaction
}

/** 一覧で見えている記事の、版ID（コメント判定に使う）とaddressの組。 */
data class ArticleEngagementTarget(
    val address: String,
    val eventId: String,
)

/**
 * 一覧の記事をまとめて問い合わせるフィルター。4本目は自分のリアクションだけを引き、
 * 1本目が上限で打ち切られても自分のリアクション状態を正しく出せるようにする。
 */
internal fun articleListEngagementFilters(
    addresses: List<String>,
    ownPubkey: String?,
    limit: Int,
): List<NostrFilter> = buildList {
    add(NostrFilter(kinds = listOf(7), aTags = addresses, limit = limit))
    add(NostrFilter(kinds = listOf(COMMENT_EVENT_KIND), rootAddressTags = addresses, limit = limit))
    add(NostrFilter(kinds = listOf(1), aTags = addresses, limit = limit))
    if (ownPubkey != null) {
        add(NostrFilter(kinds = listOf(7), authors = listOf(ownPubkey), aTags = addresses, limit = addresses.size))
        add(ownRecentDeletionsFilter(ownPubkey))
    }
}

/**
 * バッチで受信したイベントを記事ごとに集計する。判定は詳細画面と同じ関数を使う。
 * 他人のリアクション、またはコメントの受信件数が[limit]に達した場合、そのバッチの件数を下限扱いにする。
 */
internal fun summarizeArticleEngagementBatch(
    events: Collection<NostrEvent>,
    targets: List<ArticleEngagementTarget>,
    ownPubkey: String?,
    limit: Int,
    nowMillis: Long,
    isMuted: (String) -> Boolean = { false },
): Map<String, ArticleEngagementSummary> {
    val unique = withoutDeletedEvents(events.distinctBy { it.id }, events)
    val reactionLimitReached = unique.count { it.kind == 7 && it.pubkey != ownPubkey } >= limit
    val commentLimitReached = unique.count { it.kind == COMMENT_EVENT_KIND } >= limit ||
        unique.count { it.kind == 1 } >= limit
    return targets.associate { target ->
        val versionIds = setOf(target.eventId)
        val reactions = summarizeArticleReactions(unique, target.address, versionIds, ownPubkey, isMuted)
        val comments = articleTopLevelComments(unique, target.address, versionIds, isMuted)
        target.address to ArticleEngagementSummary(
            reactionCount = reactions.totalCount,
            commentCount = comments.size,
            isReactionLowerBound = reactionLimitReached,
            isCommentLowerBound = commentLimitReached,
            ownReaction = reactions.ownReaction(),
            fetchedAtMillis = nowMillis,
        )
    }
}

private fun ArticleReactionSummary.ownReaction(): OwnArticleReaction? = when {
    ownLikeEventId != null -> OwnArticleReaction.Like
    else -> ownEmojiReactionEventIds.keys.firstOrNull()
        ?.let(::reactionOptionForKey)
        ?.let(OwnArticleReaction::Emoji)
}

/** 詳細画面で取得・更新した正確な値を、一覧用の件数へ変換する。 */
internal fun articleEngagementSummaryOf(
    reactions: NoteEngagementState,
    commentCount: Int,
    nowMillis: Long,
): ArticleEngagementSummary = ArticleEngagementSummary(
    reactionCount = reactions.reactionCount,
    commentCount = commentCount,
    ownReaction = when {
        reactions.ownLikeEventId != null -> OwnArticleReaction.Like
        else -> reactions.displayOwnEmojiReactionEventIds.keys.firstOrNull()
            ?.let(::reactionOptionForKey)
            ?.let(OwnArticleReaction::Emoji)
    },
    fetchedAtMillis = nowMillis,
)

/** リアクションのキー（`unicode:…`/`custom:shortcode:url`）から選択肢を復元する。 */
fun reactionOptionForKey(key: String): ReactionOption? = when {
    key.startsWith("unicode:") -> ReactionOption.Unicode(key.removePrefix("unicode:"))
    key.startsWith("custom:") -> key.removePrefix("custom:").split(":", limit = 2)
        .takeIf { it.size == 2 }
        ?.let { (shortcode, url) -> ReactionOption.Custom(shortcode, url) }
    else -> null
}

/**
 * 可視範囲から、取得すべき記事を選ぶ。期限内の取得済み記事と取得中の記事を除き、
 * 並び順を保ったまま[batchSize]件ずつに分ける。
 */
internal fun selectArticleEngagementBatches(
    candidates: List<ArticleEngagementTarget>,
    isFresh: (String) -> Boolean,
    inFlight: Set<String>,
    batchSize: Int,
): List<List<ArticleEngagementTarget>> = candidates
    .distinctBy { it.address }
    .filterNot { isFresh(it.address) || it.address in inFlight }
    .chunked(batchSize)

/**
 * 一覧カード用の件数キャッシュ。アカウントごとに分け、addressごとの[StateFlow]を返す。
 * カードは自分の記事の分だけを購読するため、1件の更新で再描画されるのはそのカードだけになる。
 */
object ArticleEngagementSummaryStore {
    private val lock = SynchronousLock()
    private val summaries = BoundedLruCache<String, MutableStateFlow<ArticleEngagementSummary?>>(MAXIMUM_ENTRIES)

    fun flow(accountKey: String?, address: String): StateFlow<ArticleEngagementSummary?> =
        lock.withLock { entryLocked(key(accountKey, address)) }.asStateFlow()

    fun isFresh(accountKey: String?, address: String, nowMillis: Long): Boolean = lock.withLock {
        val summary = summaries[key(accountKey, address)]?.value ?: return@withLock false
        !summary.needsRefresh && nowMillis - summary.fetchedAtMillis < FRESH_DURATION_MILLIS
    }

    fun put(accountKey: String?, address: String, summary: ArticleEngagementSummary) {
        lock.withLock { entryLocked(key(accountKey, address)) }.value = summary
    }

    /** 次の可視範囲の取得で、期限に関係なく取り直させる。表示中の件数は残す。 */
    fun markStale(accountKey: String?, addresses: Collection<String>) {
        lock.withLock {
            addresses.forEach { address ->
                summaries[key(accountKey, address)]?.let { entry ->
                    entry.value = entry.value?.copy(needsRefresh = true)
                }
            }
        }
    }

    internal fun clearForTest() {
        lock.withLock {
            summaries.clear()
        }
    }

    private fun entryLocked(key: String): MutableStateFlow<ArticleEngagementSummary?> =
        summaries[key] ?: MutableStateFlow<ArticleEngagementSummary?>(null).also { summaries[key] = it }

    private fun key(accountKey: String?, address: String): String = "${accountKey.orEmpty()}|$address"

    private const val MAXIMUM_ENTRIES = 500
    const val FRESH_DURATION_MILLIS = 5 * 60 * 1_000L
}
