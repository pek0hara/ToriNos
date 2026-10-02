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
 *
 * 表示境界は、続きのあるリレーのうち最も浅いカーソルに合わせる。投稿のまばらな
 * リレーが深く遡っても、密なリレーが未取得の区間を表示範囲に含めないためである。
 * 猶予を過ぎても応答しないリレーや再試行待ちのリレーは境界の決定から外し、
 * 応答後に表示済みの範囲まで自動で追いつかせる。
 *
 * [revealsHistory] が false のときは表示境界を扱わず、[autoContinue] が true なら
 * 各リレーを続きがなくなるまで自動で取得する。復帰時の差分取得に使う。
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
    private val revealsHistory: Boolean = true,
    private val autoContinue: Boolean = false,
    private val onEvent: (NostrEvent) -> Int,
    /** この時刻以降を表示してよい。値は単調に古くなり、変わったときだけ通知する。 */
    private val onReveal: (Long) -> Unit = {},
    /** 境界は変わらないが、ページ完了で受信済みイベントを画面へ確定させる。 */
    private val onFlush: () -> Unit,
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
    private var paused = false
    private var revealedThrough: Long? = null
    /** 問い合わせ先がなかった「もっと読む」を、応答待ちのリレーが返ったら続ける。 */
    private var loadMoreDeferred = false

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
        if (started) {
            added.forEach(::requestRelay)
            if (!paused && removed.isNotEmpty()) publishReveal()
        }
        publishState()
    }

    fun start() {
        started = true
        loadMore()
    }

    /**
     * 応答待ちのリレーはそのまま残し、表示境界に届いているリレーだけ次ページへ進める。
     * 境界より深く遡ったリレーは、既に表示範囲外の投稿を持っているので問い合わせない。
     */
    fun loadMore() {
        if (!active || paused) return
        val floor = revealedThrough
        val candidates = relays.filterValues { relay ->
            !relay.opening && relay.session == null && relay.retryJob == null &&
                relay.hasMore() && (floor == null || relay.coverage() >= floor)
        }.keys
        if (candidates.isEmpty()) {
            val waiting = relays.values.filter { relay ->
                relay.hasMore() && (relay.session != null || relay.opening || relay.retryJob != null)
            }
            if (waiting.isNotEmpty()) {
                // 応答待ちのリレーが返ったら続ける。読み込み中にしておくと、画面側も止まらない。
                loadMoreDeferred = true
                foregroundLoading = true
                // 境界を決めているリレーが応答待ちなら、猶予後に遅延扱いにして表示を進める。
                if (waiting.any { (it.session != null || it.opening) && !it.lagging }) scheduleSettle()
            }
            publishReveal()
            publishState()
            return
        }
        candidates.forEach { url -> relays[url]?.catchUpPages = 0 }
        generation++
        foregroundLoading = true
        publishState()
        candidates.forEach(::requestRelay)
    }

    /**
     * タブを離れる間は通信だけ止め、リレーごとのカーソルは保持する。
     * 取得途中だったページは破棄し、[resume] で同じカーソルから取り直す。
     */
    fun pause() {
        if (!active || paused) return
        paused = true
        settleJob?.cancel()
        settleJob = null
        foregroundLoading = false
        loadMoreDeferred = false
        collectors.values.forEach { it.cancel() }
        collectors.clear()
        relays.values.forEach { relay ->
            if (relay.session != null || relay.opening || relay.retryJob != null) relay.needsResume = true
            relay.resumeWithBackoff = relay.retryJob != null
            relay.retryJob?.cancel()
            relay.retryJob = null
            relay.session?.let { session -> scope.launch { session.close() } }
            relay.session = null
            relay.opening = false
            relay.page = null
            relay.lagging = false
            relay.requestToken++
        }
        publishState()
    }

    fun resume() {
        if (!active || !paused) return
        paused = false
        val interrupted = relays.filterValues { it.needsResume }
        interrupted.forEach { (url, relay) ->
            relay.needsResume = false
            // 再試行待ちだったリレーは、タブ切り替えで待ち時間を飛ばさない。
            if (relay.resumeWithBackoff) startRetryTimer(url, relay) else requestRelay(url)
            relay.resumeWithBackoff = false
        }
        // 一時停止中に外れたリレーなどで、境界が進められる場合がある。
        publishReveal(flushIfUnchanged = false)
        publishState()
    }

    /** 手動更新では、拒否や再試行待ちで止めていたリレーにもすぐ問い合わせ直す。 */
    fun retryStalledRelays() {
        if (!active || paused) return
        relays.forEach { (url, relay) ->
            val stalled = relay.suppressed || relay.retryJob != null
            if (!stalled) return@forEach
            relay.suppressed = false
            relay.closedDisposition = null
            relay.failureCount = 0
            relay.retryJob?.cancel()
            relay.retryJob = null
            if (relay.hasMore()) requestRelay(url)
        }
        publishState()
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
        if (paused) {
            relay.needsResume = true
            return
        }
        if (relay.opening || relay.session != null) return
        val floor = revealedThrough
        val available = relay.cursors.mapIndexedNotNull { index, cursor ->
            // 表示境界より深く遡ったフィルターは、まだ表示しない投稿を取りに行かない。
            val beyondFloor = floor != null && (cursor.until ?: Long.MAX_VALUE) < floor
            if (cursor.exhausted || cursor.suppressed || beyondFloor) null
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
        val token = relay.requestToken
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
                if (relay.requestToken != token) return@launch
                relay.opening = false
                relay.page = null
                relay.lagging = false
                if (active && relays[url] === relay) {
                    scheduleRetry(url, relay, page = null)
                    publishReveal()
                }
                if (relays.values.none { it.session != null || it.opening }) foregroundLoading = false
                publishState()
                return@launch
            }
            if (!active || relays[url] !== relay || relay.requestToken != token) {
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
        relay.oldestReceived = minOf(relay.oldestReceived ?: Long.MAX_VALUE, signal.event.createdAt)
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
        relay.lagging = false
        val page = relay.page
        relay.page = null
        val succeeded = completed.outcomes[url] is RelayOutcome.Eose
        if (succeeded && page != null) {
            relay.hasCompletedInitialPage = true
            relay.failureCount = 0
            relay.closedDisposition = null
            page.requested.forEach { requested ->
                val cursor = relay.cursors[requested.index]
                val timestamps = page.events[requested.index].orEmpty().values
                advanceCursor(cursor, requested, timestamps)
            }
        } else {
            scheduleRetry(url, relay, page)
        }
        if (relays.values.none { it.session != null || it.opening }) {
            settleJob?.cancel()
            settleJob = null
            foregroundLoading = false
        }
        publishReveal()
        // 差分取得は最後まで、遅れて応答したリレーは既に表示した範囲を埋め終わるまで続けて取得する。
        val floor = revealedThrough
        // 追いつきは数ページまでにし、残りは「もっと読む」に合わせて進める。
        val behindRevealed = floor != null && relay.coverage() > floor &&
            relay.catchUpPages < MAX_CATCH_UP_PAGES
        if (succeeded && relay.hasMore() && (autoContinue || behindRevealed)) {
            if (!autoContinue) relay.catchUpPages++
            requestRelay(url)
        }
        if (loadMoreDeferred && relay.session == null && !relay.opening) {
            loadMoreDeferred = false
            loadMore()
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
        startRetryTimer(url, relay)
    }

    private fun startRetryTimer(url: String, relay: RelayState) {
        val attempt = relay.failureCount.coerceAtLeast(1)
        val delayMillis = (RETRY_BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4)))
            .coerceAtMost(RETRY_MAX_DELAY_MS)
        relay.retryJob = scope.launch {
            delay(delayMillis)
            relay.retryJob = null
            if (active && relays[url] === relay) requestRelay(url)
        }
    }

    private fun scheduleSettle() {
        if (settleJob != null) return
        settleJob = scope.launch {
            delay(settleDelayMillis)
            settleJob = null
            foregroundLoading = false
            // 通信は継続したまま、応答の遅いリレーを境界の決定から外して表示を進める。
            relays.values.forEach { relay ->
                if (relay.session != null || relay.opening) relay.lagging = true
            }
            publishReveal()
            publishState()
        }
    }

    /**
     * 境界を決めるリレーのうち最も浅いカーソルまでを表示する。
     * 該当するリレーがない（全リレーが遅延・再試行中）ときは、受信済みの分をすべて表示する。
     */
    private fun publishReveal(flushIfUnchanged: Boolean = true) {
        if (!active || paused || !started) return
        if (!revealsHistory) {
            if (flushIfUnchanged) onFlush()
            return
        }
        val current = revealedThrough
        val blocking = relays.values.filter { relay ->
            !relay.suppressed && relay.retryJob == null && !relay.lagging &&
                // 表示済みの範囲より遅れているリレーは追いつき中なので、境界を止めない。
                (current == null || relay.coverage() <= current)
        }
        val floor = if (blocking.isNotEmpty()) {
            blocking.maxOf { it.coverage() }
        } else {
            relays.values.mapNotNull { it.oldestReceived }.minOrNull() ?: return
        }
        if (current != null && floor >= current) {
            if (flushIfUnchanged) onFlush()
            return
        }
        revealedThrough = floor
        onReveal(floor)
    }

    /** このリレーから欠けなく受信済みの最古時刻。続きのないリレーは全期間を網羅している。 */
    private fun RelayState.coverage(): Long =
        cursors.filter { !it.exhausted && !it.suppressed }
            .maxOfOrNull { it.until ?: Long.MAX_VALUE }
            ?: Long.MIN_VALUE

    private fun RelayState.hasMore(): Boolean =
        !suppressed && cursors.any { !it.exhausted && !it.suppressed }

    private fun publishState() {
        if (!active) return
        val canLoadMore = relays.values.any { it.hasMore() }
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
        /** 猶予を過ぎても応答がなく、表示境界の決定から外している。 */
        var lagging: Boolean = false,
        /** 一時停止で中断した取得を、再開時に取り直す。 */
        var needsResume: Boolean = false,
        var resumeWithBackoff: Boolean = false,
        /** 「もっと読む」なしで続けた追いつき取得のページ数。 */
        var catchUpPages: Int = 0,
        /** 一時停止をまたいだ古い取得の結果を捨てるための世代。 */
        var requestToken: Int = 0,
        var oldestReceived: Long? = null,
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
        const val MAX_CATCH_UP_PAGES = 3
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
