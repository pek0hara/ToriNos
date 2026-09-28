package com.nostr.torinos.journal

import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.NostrRepository
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

internal class NostrJournalEventSource(
    private val openSession: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    private val timeoutMillis: Long = JOURNAL_FETCH_TIMEOUT_MS,
) : JournalEventSource {
    override suspend fun fetch(request: JournalFetchRequest): JournalFetchResult {
        val completed = collectFetch(
            openSession = openSession,
            spec = SubscriptionSpec(
                id = journalSubscriptionId("journal"),
                filters = request.filters,
                target = request.target,
                behavior = SubscriptionBehavior.Fetch(timeoutMillis),
            ),
        )
        return JournalFetchResult(request, completed.events, completed.complete)
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
                    NostrFilter(kinds = listOf(1, 6, 7), eTags = ids, limit = ENGAGEMENT_LIMIT),
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

private class CollectedFetch(val events: List<NostrEvent>, val complete: Boolean)

private suspend fun collectFetch(
    openSession: suspend (SubscriptionSpec) -> SubscriptionSession,
    spec: SubscriptionSpec,
    onEvent: (NostrEvent) -> Unit = {},
): CollectedFetch {
    if (spec.filters.isEmpty()) return CollectedFetch(emptyList(), complete = true)
    val session = openSession(spec)
    val events = linkedMapOf<String, NostrEvent>()
    var completion: SubscriptionSignal.FetchCompleted? = null
    try {
        session.signals.collect { signal ->
            when (signal) {
                is SubscriptionSignal.Event -> if (events.put(signal.event.id, signal.event) == null) {
                    onEvent(signal.event)
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
