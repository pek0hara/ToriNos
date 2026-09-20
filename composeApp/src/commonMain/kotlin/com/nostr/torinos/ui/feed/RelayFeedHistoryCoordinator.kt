package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.RetryDisposition
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * フィード履歴をリレー単位で取得する。
 *
 * 遅い、または停止したリレーの有限取得を別セッションに隔離し、応答したリレーの
 * カーソルを独立して進める。フィルターごとにもカーソルを持つため、投稿とコメントの
 * 件数差で片方を早く打ち切らない。
 */
internal class RelayFeedHistoryCoordinator(
    private val scope: CoroutineScope,
    private val subscriptions: FeedSubscriptionGateway,
    private val idPrefix: String,
    private val baseFilters: List<NostrFilter>,
    private val historyFloor: Long,
    private val fetchTimeoutMillis: Long,
    private val pageSize: Int,
    private val maxPageSize: Int,
    private val settleDelayMillis: Long,
    private val onEvent: (NostrEvent) -> Int,
    private val onPageBoundary: (Long?) -> Unit,
    private val onState: (RelayHistoryUiState) -> Unit,
) {
    private val relays = linkedMapOf<String, RelayState>()
    private val collectors = mutableMapOf<String, Job>()
    private var settleJob: Job? = null
    private var active = true
    private var requestSequence = 0L
    private var generation = 0
    private var foregroundLoading = false
    private var started = false

    fun updateRelays(urls: Set<String>) {
        if (!active) return
        val removed = relays.keys - urls
        removed.forEach { url ->
            collectors.remove(url)?.cancel()
            relays.remove(url)?.let { relay ->
                relay.retryJob?.cancel()
                relay.session?.let { session -> scope.launch { session.close() } }
            }
        }
        val added = urls - relays.keys
        added.forEach { url ->
            relays[url] = RelayState(
                cursors = baseFilters.map {
                    FilterCursor(until = historyFloor, limit = pageSize)
                }.toMutableList(),
            )
        }
        if (relays.values.none { it.session != null || it.opening }) foregroundLoading = false
        publishState()
        if (started) added.forEach(::requestRelay)
    }

    fun start() {
        started = true
        loadMore()
    }

    /** 応答待ちのリレーはそのまま残し、取得可能なリレーだけ次ページへ進める。 */
    fun loadMore() {
        if (!active) return
        val candidates = relays.filterValues { relay ->
            !relay.opening && relay.session == null && relay.retryJob == null &&
                relay.cursors.any { !it.exhausted && !it.suppressed }
        }.keys
        if (candidates.isEmpty()) {
            publishState()
            return
        }
        generation++
        foregroundLoading = true
        publishState()
        candidates.forEach(::requestRelay)
    }

    fun close() {
        if (!active) return
        active = false
        settleJob?.cancel()
        settleJob = null
        collectors.values.forEach { it.cancel() }
        collectors.clear()
        relays.values.forEach { relay ->
            relay.retryJob?.cancel()
            relay.session?.let { session -> scope.launch { session.close() } }
        }
        relays.clear()
    }

    private fun requestRelay(url: String) {
        val relay = relays[url] ?: return
        if (relay.opening || relay.session != null) return
        val available = relay.cursors.mapIndexedNotNull { index, cursor ->
            if (cursor.exhausted || cursor.suppressed) null
            else RequestedFilter(index, cursor.until, cursor.limit)
        }
        val requested = if (relay.isolateFilters && available.size > 1) {
            val selected = available.firstOrNull { it.index >= relay.nextFilterIndex } ?: available.first()
            relay.nextFilterIndex = (selected.index + 1) % relay.cursors.size
            listOf(selected)
        } else {
            available
        }
        if (requested.isEmpty()) {
            publishState()
            return
        }
        relay.retryJob = null
        relay.opening = true
        relay.page = Page(requested)
        val requestId = ++requestSequence
        val filters = requested.map { requestedFilter ->
            baseFilters[requestedFilter.index].copy(
                until = requestedFilter.until,
                limit = requestedFilter.limit,
            )
        }
        scope.launch {
            val session = try {
                subscriptions.open(
                    SubscriptionSpec(
                        id = "$idPrefix-history-$requestId",
                        filters = filters,
                        target = RelayTarget.Single(url),
                        behavior = SubscriptionBehavior.Fetch(fetchTimeoutMillis),
                        deduplicateEvents = false,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                relay.opening = false
                relay.page = null
                if (active && relays[url] === relay) scheduleRetry(url, relay, page = null)
                if (relays.values.none { it.session != null || it.opening }) foregroundLoading = false
                publishState()
                return@launch
            }
            if (!active || relays[url] !== relay) {
                session.close()
                return@launch
            }
            relay.opening = false
            relay.session = session
            collectors[url]?.cancel()
            collectors[url] = scope.launch { collect(url, relay, session) }
            publishState()
        }
    }

    private suspend fun collect(url: String, relay: RelayState, session: SubscriptionSession) {
        session.signals.collect { signal ->
            if (!active || relays[url] !== relay || relay.session !== session) return@collect
            when (signal) {
                is SubscriptionSignal.Event -> handleEvent(url, relay, signal)
                is SubscriptionSignal.Eose -> if (signal.relayUrl == url) scheduleSettle()
                is SubscriptionSignal.Closed -> if (signal.relayUrl == url) {
                    relay.closedDisposition = signal.retry
                }
                is SubscriptionSignal.FetchCompleted -> finishPage(url, relay, session, signal)
                else -> Unit
            }
        }
    }

    private fun handleEvent(url: String, relay: RelayState, signal: SubscriptionSignal.Event) {
        val visibleAdded = onEvent(signal.event)
        if (signal.relayUrl != url) return
        val page = relay.page ?: return
        val requested = page.requested.firstOrNull { requestedFilter ->
            signal.event.matches(baseFilters[requestedFilter.index])
        } ?: return
        page.events.getOrPut(requested.index) { linkedMapOf() }[signal.event.id] = signal.event.createdAt
        page.visibleAdded += visibleAdded
    }

    private fun finishPage(
        url: String,
        relay: RelayState,
        session: SubscriptionSession,
        completed: SubscriptionSignal.FetchCompleted,
    ) {
        if (relay.session !== session) return
        collectors.remove(url)
        relay.session = null
        val page = relay.page
        relay.page = null
        val succeeded = completed.outcomes[url] is RelayOutcome.Eose
        if (succeeded && page != null) {
            relay.hasCompletedInitialPage = true
            relay.failureCount = 0
            relay.closedDisposition = null
            var oldest: Long? = null
            page.requested.forEach { requested ->
                val cursor = relay.cursors[requested.index]
                val timestamps = page.events[requested.index].orEmpty().values
                oldest = listOfNotNull(oldest, timestamps.minOrNull()).minOrNull()
                advanceCursor(cursor, requested, timestamps)
            }
            onPageBoundary(oldest)
        } else {
            scheduleRetry(url, relay, page)
        }
        if (relays.values.none { it.session != null || it.opening }) {
            settleJob?.cancel()
            settleJob = null
            foregroundLoading = false
        }
        publishState()
    }

    private fun advanceCursor(
        cursor: FilterCursor,
        requested: RequestedFilter,
        timestamps: Collection<Long>,
    ) {
        if (timestamps.isEmpty()) {
            cursor.exhausted = true
            return
        }
        val oldest = timestamps.minOrNull() ?: return
        if (timestamps.size < requested.limit) {
            cursor.exhausted = true
            return
        }
        if (requested.until != null && oldest == requested.until) {
            if (requested.limit < maxPageSize) {
                cursor.until = requested.until
                cursor.limit = (requested.limit * 2).coerceAtMost(maxPageSize)
            } else {
                // 同一秒が上限を超える場合も停止せず、既知の範囲を確定して前の秒へ進む。
                cursor.coverageGapAt = oldest
                cursor.until = (oldest - 1).coerceAtLeast(0L)
                cursor.limit = pageSize
            }
        } else {
            // until は包含境界。境界秒を再取得し、event id の重複排除で安全にマージする。
            cursor.until = oldest
            cursor.limit = pageSize
        }
    }

    private fun scheduleRetry(url: String, relay: RelayState, page: Page?) {
        val disposition = relay.closedDisposition
        if (disposition == RetryDisposition.RetryOnFilterChange) {
            val requested = page?.requested.orEmpty()
            if (requested.size > 1) {
                // 複合REQの拒否は、次回からフィルターを1件ずつ送り原因を隔離する。
                relay.isolateFilters = true
            } else {
                requested.singleOrNull()?.let { relay.cursors[it.index].suppressed = true }
                relay.closedDisposition = null
                if (relay.cursors.any { !it.exhausted && !it.suppressed }) {
                    scope.launch { requestRelay(url) }
                }
                return
            }
        } else if (disposition == RetryDisposition.RetryAfterAuth ||
            disposition == RetryDisposition.DoNotRetry
        ) {
            relay.suppressed = true
            return
        }
        relay.failureCount++
        val delayMillis = (RETRY_BASE_DELAY_MS * (1L shl (relay.failureCount - 1).coerceAtMost(4)))
            .coerceAtMost(RETRY_MAX_DELAY_MS)
        relay.retryJob = scope.launch {
            delay(delayMillis)
            relay.retryJob = null
            if (active && relays[url] === relay) requestRelay(url)
        }
    }

    private fun scheduleSettle() {
        if (settleJob != null || !foregroundLoading) return
        settleJob = scope.launch {
            delay(settleDelayMillis)
            settleJob = null
            foregroundLoading = false
            // 通信は継続したまま、応答済みイベントをUIへ確定する。
            val oldestReceived = relays.values
                .mapNotNull { relay -> relay.page?.events?.values?.flatMap { it.values }?.minOrNull() }
                .minOrNull()
            onPageBoundary(oldestReceived)
            publishState()
        }
    }

    private fun publishState() {
        if (!active) return
        val canLoadMore = relays.values.any { relay ->
            !relay.suppressed && relay.cursors.any { !it.exhausted && !it.suppressed }
        }
        onState(
            RelayHistoryUiState(
                isLoading = foregroundLoading,
                canLoadMore = canLoadMore,
                generation = generation,
                relayCount = relays.size,
                successfulInitialRelayCount = relays.values.count { it.hasCompletedInitialPage },
                isInitialFetchSettled = relays.isNotEmpty() && relays.values.all { relay ->
                    relay.hasCompletedInitialPage || relay.suppressed
                },
                stalledRelayCount = relays.values.count { it.retryJob != null || it.suppressed },
                coverageGapCount = relays.values.sumOf { relay ->
                    relay.cursors.count { it.coverageGapAt != null }
                },
            ),
        )
    }

    private data class RelayState(
        val cursors: MutableList<FilterCursor>,
        var session: SubscriptionSession? = null,
        var opening: Boolean = false,
        var page: Page? = null,
        var retryJob: Job? = null,
        var hasCompletedInitialPage: Boolean = false,
        var failureCount: Int = 0,
        var closedDisposition: RetryDisposition? = null,
        var suppressed: Boolean = false,
        var isolateFilters: Boolean = false,
        var nextFilterIndex: Int = 0,
    )

    private data class FilterCursor(
        var until: Long? = null,
        var limit: Int = DEFAULT_PAGE_SIZE,
        var exhausted: Boolean = false,
        var suppressed: Boolean = false,
        var coverageGapAt: Long? = null,
    )

    private data class RequestedFilter(val index: Int, val until: Long?, val limit: Int)

    private data class Page(
        val requested: List<RequestedFilter>,
        val events: MutableMap<Int, LinkedHashMap<String, Long>> = mutableMapOf(),
        var visibleAdded: Int = 0,
    )

    private fun NostrEvent.matches(filter: NostrFilter): Boolean =
        filter.kinds?.let { kind in it } ?: true

    private companion object {
        const val DEFAULT_PAGE_SIZE = 30
        const val RETRY_BASE_DELAY_MS = 2_000L
        const val RETRY_MAX_DELAY_MS = 30_000L
    }
}

internal data class RelayHistoryUiState(
    val isLoading: Boolean,
    val canLoadMore: Boolean,
    val generation: Int,
    val relayCount: Int,
    val successfulInitialRelayCount: Int,
    val isInitialFetchSettled: Boolean,
    val stalledRelayCount: Int,
    val coverageGapCount: Int,
)
