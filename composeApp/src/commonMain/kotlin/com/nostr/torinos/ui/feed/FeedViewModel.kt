package com.nostr.torinos.ui.feed

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.NostrRepository
import kotlinx.coroutines.Job
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FeedViewModel(
    private val authorPubkey: String? = null,
) : SafeViewModel() {

    data class UiState(
        val events: List<NostrEvent> = emptyList(),
        val profiles: Map<String, NostrProfile> = emptyMap(),
        val reactionCounts: Map<String, Int> = emptyMap(),
        val replyCounts: Map<String, Int> = emptyMap(),
        val canLoadMore: Boolean = false,
        /** true = 初回 EOSE 待ち（ローディングスピナー表示） */
        val isInitialLoad: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val shortKey = authorPubkey?.take(16) ?: "global"
    private val feedSubId = "feed-$shortKey"
    private val historySubId = "hist-$shortKey"
    private val profileSubId = "prof-$shortKey"
    private val reactionSubId = "reac-$shortKey"
    private val replySubId = "repl-$shortKey"

    private val subscriptionJobs = mutableListOf<Job>()
    private val pendingPubkeys = mutableSetOf<String>()
    private var profileBatchJob: Job? = null
    private val watchedEventIds = linkedSetOf<String>()
    private var engagementBatchJob: Job? = null
    private val seenReactionIds = mutableSetOf<String>()
    private val seenReplyIds = mutableSetOf<String>()
    private val seenEventIds = linkedSetOf<String>()
    private var subscriptionsStarted = false

    private var oldestCreatedAt: Long? = null
    private var loadingMore = false
    private var lastHistoryBatchReceivedCount = 0
    private var lastHistoryBatchUniqueCount = 0
    private var receivedEoseCount = 0
    private var expectedEoseCount = 1
    private var initialHistoryRequested = false

    init {
        startSubscriptions()
    }

    fun loadMore() {
        if (loadingMore || !_state.value.canLoadMore) return
        launch {
            requestHistoryPage(until = oldestCreatedAt?.minus(1))
        }
    }

    fun startSubscriptions() {
        if (subscriptionsStarted) return
        subscriptionsStarted = true

        subscriptionJobs += launch {
            val current = _state.value
            if (current.isInitialLoad && current.events.isEmpty() && !initialHistoryRequested) {
                // 初回のみ履歴ページを取得
                requestHistoryPage(until = null)
            } else {
                // タブ再表示時はライブ購読だけ再開（履歴は再取得しない）
                val nowSec = Clock.System.now().epochSeconds
                NostrRepository.subscribe(
                    feedSubId,
                    NostrFilter(
                        kinds = listOf(1),
                        authors = authorPubkey?.let { listOf(it) },
                        since = nowSec,
                    ),
                )
            }
        }

        // フィードイベント収集（ライブ：feedSubId）
        subscriptionJobs += launch {
            NostrRepository.events(feedSubId).collect { event ->
                val parentId = event.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                if (parentId != null) {
                    scheduleEngagementFetch(parentId)
                    return@collect
                }
                appendEvent(event)
                scheduleProfileFetch(event.pubkey)
                scheduleEngagementFetch(event.id)
            }
        }

        // 過去ページイベント収集（historySubId）
        subscriptionJobs += launch {
            NostrRepository.events(historySubId).collect { event ->
                lastHistoryBatchReceivedCount++
                val parentId = event.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                if (parentId != null) {
                    scheduleEngagementFetch(parentId)
                    return@collect
                }
                lastHistoryBatchUniqueCount += appendEvent(event)
                scheduleProfileFetch(event.pubkey)
                scheduleEngagementFetch(event.id)
            }
        }

        // EOSE でローディング解除
        subscriptionJobs += launch {
            NostrRepository.eose(historySubId).collect {
                receivedEoseCount++
                if (receivedEoseCount >= expectedEoseCount) {
                    onHistoryPageCompleted()
                }
            }
        }

        // タイムアウトフォールバック：10 秒経っても EOSE が来なければスピナーを消す
        subscriptionJobs += launch {
            delay(10_000)
            val current = _state.value
            if (current.isInitialLoad && current.events.isEmpty()) {
                loadingMore = false
                _state.value = current.copy(isInitialLoad = false, canLoadMore = false)
                NostrRepository.close(historySubId)
            }
        }

        // プロフィール受信（kind:0）
        subscriptionJobs += launch {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                pendingPubkeys.remove(event.pubkey)
                _state.value = _state.value.copy(
                    profiles = _state.value.profiles + (event.pubkey to profile),
                )
            }
        }

        // リアクション受信（kind:7）
        subscriptionJobs += launch {
            NostrRepository.events(reactionSubId).collect { event ->
                if (event.kind != 7) return@collect
                if (!seenReactionIds.add(event.id)) return@collect
                val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                    ?: return@collect
                val cur = _state.value
                _state.value = cur.copy(
                    reactionCounts = cur.reactionCounts +
                        (targetId to (cur.reactionCounts[targetId] ?: 0) + 1),
                )
            }
        }

        // リプライ受信（kind:1 with e-tag）
        subscriptionJobs += launch {
            NostrRepository.events(replySubId).collect { event ->
                if (event.kind != 1) return@collect
                if (!seenReplyIds.add(event.id)) return@collect
                val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                    ?: return@collect
                val cur = _state.value
                _state.value = cur.copy(
                    replyCounts = cur.replyCounts +
                        (targetId to (cur.replyCounts[targetId] ?: 0) + 1),
                )
            }
        }
    }

    fun stopSubscriptions() {
        if (!subscriptionsStarted) return
        subscriptionsStarted = false
        subscriptionJobs.forEach { it.cancel() }
        subscriptionJobs.clear()
        profileBatchJob?.cancel()
        engagementBatchJob?.cancel()
        if (_state.value.isInitialLoad && _state.value.events.isEmpty()) {
            initialHistoryRequested = false
        }
        NostrRepository.close(feedSubId)
        NostrRepository.close(historySubId)
        NostrRepository.close(profileSubId)
        NostrRepository.close(reactionSubId)
        NostrRepository.close(replySubId)
    }

    override fun onCleared() {
        super.onCleared()
        stopSubscriptions()
    }

    private suspend fun requestHistoryPage(until: Long?) {
        loadingMore = true
        lastHistoryBatchReceivedCount = 0
        lastHistoryBatchUniqueCount = 0
        receivedEoseCount = 0
        expectedEoseCount = NostrRepository.relayCount.coerceAtLeast(1)
        _state.value = _state.value.copy(canLoadMore = false)

        // 初回のみライブ購読も開始（since=現在時刻でライブイベントのみ）
        if (until == null) {
            initialHistoryRequested = true
            val nowSec = Clock.System.now().epochSeconds
            NostrRepository.subscribe(
                feedSubId,
                NostrFilter(
                    kinds = listOf(1),
                    authors = authorPubkey?.let { listOf(it) },
                    since = nowSec,
                ),
            )
        }
        NostrRepository.subscribe(
            historySubId,
            NostrFilter(
                kinds = listOf(1),
                authors = authorPubkey?.let { listOf(it) },
                until = until,
                limit = FEED_PAGE_SIZE,
            ),
        )
    }

    private fun onHistoryPageCompleted() {
        if (!loadingMore) return
        loadingMore = false
        // リプライ等がフィルタされても受信件数が上限に達していれば次ページがある
        val hasMore = lastHistoryBatchReceivedCount >= FEED_PAGE_SIZE
        _state.value = _state.value.copy(canLoadMore = hasMore, isInitialLoad = false)
        if (!hasMore) NostrRepository.close(historySubId)
    }

    /** イベントをリストに追加し、追加できた件数（0 or 1）を返す */
    private fun appendEvent(event: NostrEvent): Int {
        if (event.kind != 1) return 0
        if (!seenEventIds.add(event.id)) return 0
        val cur = _state.value
        val updated = (cur.events + event).sortedByDescending { it.createdAt }
        oldestCreatedAt = updated.lastOrNull()?.createdAt
        _state.value = cur.copy(events = updated)
        return 1
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in _state.value.profiles || pubkey in pendingPubkeys) return
        pendingPubkeys.add(pubkey)
        profileBatchJob?.cancel()
        profileBatchJob = launch {
            delay(500)
            if (pendingPubkeys.isEmpty()) return@launch
            val authors = pendingPubkeys.toList()
            pendingPubkeys.removeAll(authors)
            NostrRepository.subscribe(
                profileSubId,
                NostrFilter(kinds = listOf(0), authors = authors),
            )
        }
    }

    private fun scheduleEngagementFetch(eventId: String) {
        if (!watchedEventIds.add(eventId)) return
        while (watchedEventIds.size > MAX_TRACKED_ENGAGEMENT_EVENTS) {
            watchedEventIds.remove(watchedEventIds.first())
        }
        engagementBatchJob?.cancel()
        engagementBatchJob = launch {
            delay(500)
            val ids = watchedEventIds.toList()
            NostrRepository.subscribe(reactionSubId, NostrFilter(kinds = listOf(7), eTags = ids))
            NostrRepository.subscribe(replySubId, NostrFilter(kinds = listOf(1), eTags = ids))
        }
    }

    companion object {
        private const val FEED_PAGE_SIZE = 30
        private const val MAX_TRACKED_ENGAGEMENT_EVENTS = 20
    }
}
