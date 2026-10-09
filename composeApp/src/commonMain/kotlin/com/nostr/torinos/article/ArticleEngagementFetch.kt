package com.nostr.torinos.article

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * 記事のリアクションとコメントを有効な全リレーから有限取得し、リレーごとの EOSE のたびにそれまでの分を渡す。
 *
 * 全リレーの完了（またはタイムアウト）後、受け取った中に自分のイベントがあれば、それらへの自分の取り消し（kind 5）だけを
 * もう1回問い合わせ、合わせて`isComplete = true`で渡す。自分の取り消しを件数の問い合わせに含めると、
 * 記事に関係のない削除イベントまで毎回受け取ることになるため分ける。確認に失敗しても受け取った分で完了する。
 */
internal suspend fun fetchArticleEngagementEvents(
    filters: List<NostrFilter>,
    ownPubkey: String?,
    open: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    onSnapshot: suspend (events: List<NostrEvent>, isComplete: Boolean) -> Unit,
) {
    val events = linkedMapOf<String, NostrEvent>()
    collectFetch(filters, "article-engagement", open) { snapshot ->
        onSnapshot(snapshot, false)
    }.forEach { events[it.id] = it }
    val ownIds = ownPubkey?.let { ownEventIdsForDeletionCheck(events.values, it) }.orEmpty()
    if (ownPubkey != null && ownIds.isNotEmpty()) {
        try {
            collectFetch(listOf(ownDeletionCheckFilter(ownPubkey, ownIds)), "article-own-deletions", open, onEose = null)
                .forEach { events[it.id] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 件数は補助表示のため、確認できなくても受け取った分で出す。
        }
    }
    onSnapshot(events.values.toList(), true)
}

/** 有限購読で受け取ったイベントを返す。[onEose]を渡すと、新しいイベントが増えた EOSE のたびに呼ぶ。 */
private suspend fun collectFetch(
    filters: List<NostrFilter>,
    prefix: String,
    open: suspend (SubscriptionSpec) -> SubscriptionSession,
    onEose: (suspend (List<NostrEvent>) -> Unit)?,
): List<NostrEvent> {
    val events = linkedMapOf<String, NostrEvent>()
    // 0件の EOSE でも最初の1回は渡す（件数0の記事の「読み込み中」を早く終えるため）。
    var deliveredCount = -1
    val session = open(
        SubscriptionSpec(
            id = articleSubscriptionId(prefix),
            filters = filters,
            behavior = SubscriptionBehavior.Fetch(timeoutMillis = ARTICLE_ENGAGEMENT_FETCH_TIMEOUT_MS),
        ),
    )
    try {
        // FetchCompleted が来ない異常時にも止まるよう、購読のタイムアウトより少し長く待つ。
        withTimeoutOrNull(ARTICLE_ENGAGEMENT_FETCH_TIMEOUT_MS + 1_000) {
            session.signals.takeWhile { signal ->
                when (signal) {
                    is SubscriptionSignal.Event -> events[signal.event.id] = signal.event
                    is SubscriptionSignal.Eose -> if (onEose != null && events.size != deliveredCount) {
                        deliveredCount = events.size
                        onEose(events.values.toList())
                    }
                    else -> Unit
                }
                signal !is SubscriptionSignal.FetchCompleted
            }.collect { }
        }
    } finally {
        withContext(NonCancellable) { session.close() }
    }
    return events.values.toList()
}

/** 取り消しを確かめる自分のイベント（受け取った順、最大[OWN_DELETION_CHECK_LIMIT]件）。 */
internal fun ownEventIdsForDeletionCheck(events: Collection<NostrEvent>, ownPubkey: String): List<String> =
    events.asSequence()
        .filter { it.pubkey == ownPubkey && it.kind != DELETION_KIND }
        .map { it.id }
        .distinct()
        .take(OWN_DELETION_CHECK_LIMIT)
        .toList()

/** [eventIds]への自分の取り消し（kind 5）。 */
internal fun ownDeletionCheckFilter(ownPubkey: String, eventIds: List<String>): NostrFilter =
    NostrFilter(
        kinds = listOf(DELETION_KIND),
        authors = listOf(ownPubkey),
        eTags = eventIds,
        limit = OWN_DELETION_CHECK_LIMIT,
    )

/** 購読ID。`max_subid_length`が小さいリレー（50文字など）に拒否されないよう短くする。 */
internal fun articleSubscriptionId(prefix: String): String =
    "$prefix-${Random.nextLong().toULong().toString(36)}"

private const val DELETION_KIND = 5
private const val OWN_DELETION_CHECK_LIMIT = 500
private const val ARTICLE_ENGAGEMENT_FETCH_TIMEOUT_MS = 8_000L
