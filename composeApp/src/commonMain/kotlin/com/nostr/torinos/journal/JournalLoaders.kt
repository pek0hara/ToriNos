package com.nostr.torinos.journal

import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.EventByIdFetcher
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayLimits
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlin.random.Random

/** [JournalFetchRequest]を1回の有限購読で実行する。 */
internal fun interface JournalEventSource {
    suspend fun fetch(request: JournalFetchRequest): JournalFetchResult
}

/** 投稿IDの集合に付いたリアクション・返信・リポスト・引用を取得する。全リレー完了ならtrue。 */
internal fun interface JournalEngagementSource {
    suspend fun fetch(noteIds: Set<String>, relayUrl: String?, onEvent: (NostrEvent) -> Unit): Boolean
}

/**
 * リレーごと・フィルターごとの件数から、件数の上限で打ち切られたかを判定する。
 * 件数を数えるため、他のリレーで受信済みのイベントを省く重複排除とキャッシュの再生を切る。結果はIDで重複を除く。
 */
internal class NostrJournalEventSource(
    private val openSession: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    private val timeoutMillis: Long = JOURNAL_FETCH_TIMEOUT_MS,
    private val maxLimit: (relayUrl: String) -> Int? = RelayLimits::maxLimit,
) : JournalEventSource {
    override suspend fun fetch(request: JournalFetchRequest): JournalFetchResult {
        val received = mutableListOf<Pair<String, NostrEvent>>()
        val completed = collectFetch(
            openSession = openSession,
            spec = SubscriptionSpec(
                id = journalSubscriptionId("journal"),
                filters = request.filters,
                target = request.target,
                behavior = SubscriptionBehavior.Fetch(timeoutMillis),
                deduplicateEvents = false,
                replayCachedEvents = false,
            ),
            onRelayEvent = { relayUrl, event -> received += relayUrl to event },
        )
        val requested = request.filters.mapNotNull { it.limit }.minOrNull()
        val truncatedAt = requested?.let {
            journalTruncatedAt(journalFilterTallies(received, request.filters)) { relayUrl ->
                journalPageThreshold(maxLimit(relayUrl), requested)
            }
        }
        return JournalFetchResult(request, completed.events, completed.complete, truncatedAt)
    }
}

internal class NostrJournalEngagementSource(
    private val openSession: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    private val timeoutMillis: Long = JOURNAL_ENGAGEMENT_TIMEOUT_MS,
) : JournalEngagementSource {
    override suspend fun fetch(
        noteIds: Set<String>,
        relayUrl: String?,
        onEvent: (NostrEvent) -> Unit,
    ): Boolean {
        val ids = noteIds.toList()
        return collectFetch(
            openSession = openSession,
            spec = SubscriptionSpec(
                id = journalSubscriptionId("journal-engagement"),
                filters = listOf(
                    NostrFilter(kinds = listOf(1, 6, 16, 7), eTags = ids, limit = ENGAGEMENT_LIMIT),
                    NostrFilter(
                        kinds = listOf(COMMENT_EVENT_KIND),
                        rootKindTags = listOf("1"),
                        eTags = ids,
                        limit = ENGAGEMENT_LIMIT,
                    ),
                    NostrFilter(
                        kinds = listOf(COMMENT_EVENT_KIND),
                        rootKindTags = listOf("1"),
                        rootEventTags = ids,
                        limit = ENGAGEMENT_LIMIT,
                    ),
                    NostrFilter(kinds = listOf(1), qTags = ids, limit = ENGAGEMENT_LIMIT),
                ),
                target = relayUrl?.let(RelayTarget::Single) ?: RelayTarget.AllEnabled,
                behavior = SubscriptionBehavior.Fetch(timeoutMillis),
            ),
            onEvent = onEvent,
        ).complete
    }
}

/** 行の表示に必要な参照先（返信の親・引用・アクティビティの対象）をIDで取得する。 */
internal fun interface JournalReferenceSource {
    suspend fun fetch(ids: Set<String>, relayUrl: String?, onEvent: (NostrEvent) -> Unit)
}

/**
 * 参照先はまず指定のリレーに問い合わせ、見つからなかったIDだけをほかの有効なリレーへ問い合わせる。
 * ジャーナルの参照先はほとんどが指定のリレーにあるため、最初から全リレーへ送ると大半が無駄になる。
 * 指定がなければ有効な全リレーへ1回で問い合わせる。
 */
internal class NostrJournalReferenceSource(
    private val fetchFrom: suspend (Set<String>, RelayTarget, (NostrEvent) -> Unit) -> Unit =
        EventByIdFetcher().let { fetcher -> { ids, target, onEvent -> fetcher.fetch(ids, target, onEvent) } },
    private val enabledRelayUrls: () -> List<String> = RelayStore::enabledRelayUrlsSnapshot,
    private val chunkSize: Int = REFERENCE_CHUNK_SIZE,
) : JournalReferenceSource {
    override suspend fun fetch(ids: Set<String>, relayUrl: String?, onEvent: (NostrEvent) -> Unit) {
        if (ids.isEmpty()) return
        if (relayUrl == null) {
            fetchChunked(ids, RelayTarget.AllEnabled, onEvent)
            return
        }
        val remaining = ids.toMutableSet()
        fetchChunked(ids, RelayTarget.Single(relayUrl)) { event ->
            remaining -= event.id
            onEvent(event)
        }
        if (remaining.isEmpty()) return
        val others = enabledRelayUrls().filterTo(linkedSetOf()) { it != relayUrl }
        if (others.isEmpty()) return
        fetchChunked(remaining, RelayTarget.Explicit(others), onEvent)
    }

    private suspend fun fetchChunked(ids: Set<String>, target: RelayTarget, onEvent: (NostrEvent) -> Unit) {
        ids.chunked(chunkSize).forEach { chunk -> fetchFrom(chunk.toSet(), target, onEvent) }
    }
}

/** [pubkey]が出した・受けたやりとりを[since]以降から集める。メンバー候補の並び替えに使う。 */
internal fun interface JournalInteractionSource {
    suspend fun fetch(pubkey: String, since: Long): List<NostrEvent>
}

internal class NostrJournalInteractionSource(
    private val openSession: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    private val timeoutMillis: Long = JOURNAL_FETCH_TIMEOUT_MS,
) : JournalInteractionSource {
    override suspend fun fetch(pubkey: String, since: Long): List<NostrEvent> =
        collectFetch(
            openSession = openSession,
            spec = SubscriptionSpec(
                id = journalSubscriptionId("journal-interactions"),
                filters = listOf(
                    NostrFilter(
                        kinds = JournalInteractionEventKinds,
                        authors = listOf(pubkey),
                        since = since,
                        limit = INTERACTION_LIMIT,
                    ),
                    NostrFilter(
                        kinds = JournalInteractionEventKinds,
                        pTags = listOf(pubkey),
                        since = since,
                        limit = INTERACTION_LIMIT,
                    ),
                ),
                target = RelayTarget.AllEnabled,
                behavior = SubscriptionBehavior.Fetch(timeoutMillis),
            ),
        ).events
}

private class CollectedFetch(val events: List<NostrEvent>, val complete: Boolean)

private suspend fun collectFetch(
    openSession: suspend (SubscriptionSpec) -> SubscriptionSession,
    spec: SubscriptionSpec,
    onEvent: (NostrEvent) -> Unit = {},
    onRelayEvent: (relayUrl: String, NostrEvent) -> Unit = { _, _ -> },
): CollectedFetch {
    if (spec.filters.isEmpty()) return CollectedFetch(emptyList(), complete = true)
    val session = openSession(spec)
    val events = linkedMapOf<String, NostrEvent>()
    var completion: SubscriptionSignal.FetchCompleted? = null
    try {
        session.signals.collect { signal ->
            when (signal) {
                is SubscriptionSignal.Event -> {
                    onRelayEvent(signal.relayUrl, signal.event)
                    if (events.put(signal.event.id, signal.event) == null) onEvent(signal.event)
                }
                is SubscriptionSignal.FetchCompleted -> completion = signal
                else -> Unit
            }
        }
    } finally {
        runCatching { session.close() }
    }
    return CollectedFetch(
        events = events.values.toList(),
        complete = completion?.let(::shouldCommitJournalFetch) == true,
    )
}

private fun journalSubscriptionId(prefix: String): String =
    "$prefix-${Random.nextLong().toULong()}"

private const val JOURNAL_FETCH_TIMEOUT_MS = 8_000L
private const val JOURNAL_ENGAGEMENT_TIMEOUT_MS = 8_000L
private const val ENGAGEMENT_LIMIT = 500
private const val INTERACTION_LIMIT = 1_000
private const val REFERENCE_CHUNK_SIZE = 200
