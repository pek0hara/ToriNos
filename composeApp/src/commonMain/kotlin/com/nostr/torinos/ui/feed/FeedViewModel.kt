package com.nostr.torinos.ui.feed

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.engagement.EngagementAction
import com.nostr.torinos.engagement.EngagementOperationId
import com.nostr.torinos.engagement.EngagementReducer
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.NoteEngagementCommand
import com.nostr.torinos.engagement.NoteEngagementService
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.engagement.NoteTarget
import com.nostr.torinos.engagement.PendingEngagementOperation
import com.nostr.torinos.engagement.displayOwnEmojiReactionEventIds
import com.nostr.torinos.engagement.isRepostedByMe
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FeedViewModel(
    private val accountSession: AccountSession? = null,
    private val authorPubkey: String? = null,
    private val authorPubkeys: List<String>? = authorPubkey?.let { listOf(it) },
    private val relayUrl: String? = null,
    private val autoStart: Boolean = true,
    private val includeRepostsInFeed: Boolean = false,
    private val includeRepliesInFeed: Boolean = false,
    private val hashtag: String? = null,
    private val filterMutedUsers: Boolean = true,
) : SafeViewModel() {

    data class UiState(
        val events: List<NostrEvent> = emptyList(),
        val profiles: Map<String, NostrProfile> = emptyMap(),
        val reactionCounts: Map<String, Int> = emptyMap(),
        val likeReactionCounts: Map<String, Int> = emptyMap(),
        val customReactions: Map<String, List<CustomReaction>> = emptyMap(),
        val unicodeReactions: Map<String, List<UnicodeReaction>> = emptyMap(),
        val replyCounts: Map<String, Int> = emptyMap(),
        val replies: Map<String, List<NostrEvent>> = emptyMap(),
        val repostCounts: Map<String, Int> = emptyMap(),
        val quotedEvents: Map<String, NostrEvent> = emptyMap(),
        val repostedByPubkeys: Map<String, String> = emptyMap(),
        /** postId → 自分のリアクションイベントID */
        val likedReactions: Map<String, String> = emptyMap(),
        /** postId → (絵文字リアクションキー → 自分のリアクションイベントID) */
        val ownEmojiReactionEventIds: Map<String, Map<String, String>> = emptyMap(),
        /** postId → 自分のリポストイベントID */
        val repostedEvents: Map<String, String> = emptyMap(),
        val pendingEngagementOperations: Map<String, Map<EngagementSlot, PendingEngagementOperation>> = emptyMap(),
        val engagementError: String? = null,
        val canLoadMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        /** true = 初回 EOSE 待ち（ローディングスピナー表示） */
        val isInitialLoad: Boolean = true,
        /** true = 先頭からの手動更新中 */
        val isRefreshing: Boolean = false,
    ) {
        fun isLiked(eventId: String): Boolean = likedReactions.containsKey(eventId) ||
            pendingEngagementOperations[eventId]?.get(EngagementSlot.Reaction)?.request is EngagementRequest.AddLike

        fun displayOwnEmojiReactionEventIds(eventId: String): Map<String, String> =
            noteEngagement(eventId).displayOwnEmojiReactionEventIds

        fun isReposted(eventId: String): Boolean = noteEngagement(eventId).isRepostedByMe
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var pendingFeedState = UiState()
    private var feedStateEmitJob: Job? = null

    private val instanceKey = nextInstanceKey()
    private val shortKey = authorPubkey?.take(16) ?: authorPubkeys?.hashCode()?.toString() ?: "global"
    private val subscriptionJobs = mutableListOf<Job>()
    private val historyCollectorJobs = mutableListOf<Job>()
    private var subscriptionIds: SubscriptionIds? = null
    private var liveSession: SubscriptionSession? = null
    private var engagementSession: SubscriptionSession? = null
    private var currentHistorySession: SubscriptionSession? = null
    private var subscriptionGeneration = 0
    private var historyRequestGeneration = 0
    private val requestedProfilePubkeys = mutableSetOf<String>()
    private var refreshIndicatorTimeoutJob: Job? = null
    private val watchedEventIds = linkedSetOf<String>()
    private var engagementBatchJob: Job? = null
    private val seenReactionIds = linkedSetOf<String>()
    private val seenReplyIds = linkedSetOf<String>()
    private val seenRepostIds = linkedSetOf<String>()
    private val seenQuoteRepostIds = linkedSetOf<String>()
    private val seenEventIds = linkedSetOf<String>()
    private val receivedReactionEvents = linkedMapOf<String, NostrEvent>()
    private val receivedRepostEvents = linkedMapOf<String, NostrEvent>()
    private val rawEvents = linkedMapOf<String, NostrEvent>()
    private val canonicalEvents = linkedMapOf<String, NostrEvent>()
    private val eventSortTimes = mutableMapOf<String, Long>()
    private val pendingQuoteIds = linkedSetOf<String>()
    private val pendingRepostTargets = mutableMapOf<String, PendingRepostTarget>()
    private var subscriptionsStarted = false
    private var ownPubkey: String? = null
    private val noteEngagementService = NoteEngagementService(accountSession?.signer)
    private var nextEngagementOperationId = 0L

    private var oldestCreatedAt: Long? = null
    private var nextHistoryUntil: Long? = null
    private var activeHistoryUntil: Long? = null
    private var shouldRetryHistoryPage = false
    private var loadingMore = false
    private var isGapFill = false
    private var lastHistoryBatchReceivedCount = 0
    private var lastHistoryBatchUniqueCount = 0
    private var lastHistoryBatchOldestCreatedAt: Long? = null
    private var consecutiveEmptyHistoryPages = 0
    private var initialHistoryRequested = false
    private var manualRefreshRequested = false
    private val relayTarget: RelayTarget = relayUrl?.let(RelayTarget::Single) ?: RelayTarget.AllEnabled

    init {
        if (isWriteSupported) {
            launch {
                ownPubkey = accountSession?.pubkey
                reconcileOwnEngagement()
            }
        }
        launch {
            ProfileRepository.observeAll().collect { cachedProfiles ->
                val currentProfiles = currentFeedState().profiles
                val relevantPubkeys = currentProfiles.keys + requestedProfilePubkeys
                if (relevantPubkeys.isEmpty()) return@collect
                val updatedProfiles = cachedProfiles.filterKeys { it in relevantPubkeys }
                if (updatedProfiles.all { (pubkey, profile) -> currentProfiles[pubkey] == profile }) {
                    return@collect
                }
                requestedProfilePubkeys.removeAll(updatedProfiles.keys)
                updateFeedState(immediate = false) { state ->
                    state.copy(profiles = state.profiles + updatedProfiles)
                }
            }
        }
        if (autoStart) startSubscriptions()
    }

    private fun currentFeedState(): UiState = pendingFeedState

    private fun setFeedState(value: UiState, immediate: Boolean = true) {
        pendingFeedState = value
        if (immediate) {
            emitFeedStateNow()
        } else {
            scheduleFeedStateEmit()
        }
    }

    private fun updateFeedState(
        immediate: Boolean = true,
        transform: (UiState) -> UiState,
    ) {
        setFeedState(transform(pendingFeedState), immediate = immediate)
    }

    private fun scheduleFeedStateEmit() {
        if (feedStateEmitJob?.isActive == true) return
        feedStateEmitJob = launch {
            delay(FEED_STATE_EMIT_DELAY_MS)
            emitFeedStateNow()
        }
    }

    private fun emitFeedStateNow() {
        feedStateEmitJob?.cancel()
        feedStateEmitJob = null
        _state.value = pendingFeedState
    }

    fun injectProfile(pubkey: String, profile: com.nostr.torinos.model.NostrProfile) {
        ProfileRepository.applyOptimistic(pubkey, profile)
        val currentProfile = ProfileRepository.getCached(pubkey) ?: profile
        if (currentFeedState().profiles[pubkey] == currentProfile) return
        updateFeedState { it.copy(profiles = it.profiles + (pubkey to currentProfile)) }
    }

    fun deleteEvent(eventId: String) {
        launch {
            val signer = accountSession?.signer ?: return@launch
            runCatching {
                val deletion = signer.sign(
                    content = "",
                    kind = 5,
                    tags = listOf(listOf("e", eventId)),
                )
                NostrRepository.publish(deletion)
            }
            val cur = currentFeedState()
            updateEvents(cur.events.filter { it.id != eventId }, immediate = true)
            seenEventIds.remove(eventId)
            rawEvents.remove(eventId)
            canonicalEvents.remove(eventId)
            eventSortTimes.remove(eventId)
        }
    }

    fun consumeEngagementError() {
        updateFeedState { it.copy(engagementError = null) }
    }

    fun react(eventId: String, eventPubkey: String) {
        runEngagementOperation(
            eventId = eventId,
            request = EngagementRequest.AddLike,
            command = NoteEngagementCommand.AddLike(NoteTarget(eventId, eventPubkey)),
            failureMessage = "リアクションの送信に失敗しました",
        )
    }

    fun unreact(eventId: String) {
        val reactionEventId = currentFeedState().likedReactions[eventId] ?: return
        runEngagementOperation(
            eventId = eventId,
            request = EngagementRequest.RemoveLike,
            command = NoteEngagementCommand.RemoveReaction(reactionEventId),
            failureMessage = "リアクションの解除に失敗しました",
        )
    }

    fun reactWithEmoji(eventId: String, eventPubkey: String, option: ReactionOption) {
        runEngagementOperation(
            eventId = eventId,
            request = EngagementRequest.AddEmoji(option),
            command = NoteEngagementCommand.AddEmoji(NoteTarget(eventId, eventPubkey), option),
            failureMessage = "リアクションの送信に失敗しました",
        )
    }

    fun unreactWithEmoji(eventId: String, option: ReactionOption) {
        val reactionEventId = currentFeedState().ownEmojiReactionEventIds[eventId]?.get(option.key) ?: return
        runEngagementOperation(
            eventId = eventId,
            request = EngagementRequest.RemoveEmoji(option),
            command = NoteEngagementCommand.RemoveReaction(reactionEventId),
            failureMessage = "リアクションの解除に失敗しました",
        )
    }

    fun repost(event: NostrEvent) {
        val eventToRepost = canonicalEvents[event.id] ?: event
        runEngagementOperation(
            eventId = event.id,
            request = EngagementRequest.AddRepost,
            command = NoteEngagementCommand.AddRepost(eventToRepost),
            failureMessage = "リポストの送信に失敗しました",
        )
    }

    fun unrepost(eventId: String) {
        val repostEventId = currentFeedState().repostedEvents[eventId] ?: return
        runEngagementOperation(
            eventId = eventId,
            request = EngagementRequest.RemoveRepost,
            command = NoteEngagementCommand.RemoveRepost(repostEventId),
            failureMessage = "リポストの解除に失敗しました",
        )
    }

    private fun runEngagementOperation(
        eventId: String,
        request: EngagementRequest,
        command: NoteEngagementCommand,
        failureMessage: String,
    ) {
        val operationId = EngagementOperationId("feed-${++nextEngagementOperationId}")
        val before = currentFeedState().noteEngagement(eventId)
        val optimistic = EngagementReducer.reduce(before, EngagementAction.Begin(operationId, request))
        if (optimistic == before) return
        updateFeedState { it.withEngagement(eventId, optimistic).copy(engagementError = null) }
        launch {
            var committed = false
            var failure: Throwable? = null
            try {
                val published = noteEngagementService.execute(command) { signed ->
                    when (command) {
                        is NoteEngagementCommand.AddLike,
                        is NoteEngagementCommand.AddEmoji,
                        -> rememberSeenId(seenReactionIds, signed.id)
                        is NoteEngagementCommand.AddRepost -> rememberSeenId(seenRepostIds, signed.id)
                        is NoteEngagementCommand.RemoveReaction,
                        is NoteEngagementCommand.RemoveRepost,
                        -> Unit
                    }
                }.getOrThrow()
                updateFeedState {
                    val current = it.noteEngagement(eventId)
                    it.withEngagement(
                        eventId,
                        EngagementReducer.reduce(current, EngagementAction.Commit(operationId, published.id)),
                    )
                }
                committed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            } finally {
                if (!committed) {
                    updateFeedState {
                        val current = it.noteEngagement(eventId)
                        it.withEngagement(
                            eventId,
                            EngagementReducer.reduce(current, EngagementAction.Rollback(operationId)),
                        )
                    }
                }
            }
            if (failure != null) updateFeedState { it.copy(engagementError = failureMessage) }
        }
    }

    fun reportEvent(event: NostrEvent, reason: String, detail: String) {
        accountSession?.muteStore?.mute(event.pubkey)
        rebuildFilteredEvents()
        launch {
            val signer = accountSession?.signer ?: return@launch
            runCatching {
                val report = signer.sign(
                    content = detail,
                    kind = 1984,
                    tags = listOf(
                        listOf("e", event.id, "", reason),
                        listOf("p", event.pubkey),
                    ),
                )
                NostrRepository.publish(report)
            }
        }
    }

    fun loadMore() {
        if (loadingMore || !currentFeedState().canLoadMore) return
        launch {
            val until = if (shouldRetryHistoryPage) nextHistoryUntil else nextHistoryUntil ?: oldestCreatedAt?.minus(1)
            requestHistoryPage(until = until)
        }
    }

    fun refresh() {
        if (currentFeedState().isRefreshing) return
        launch {
            setFeedState(currentFeedState().copy(isRefreshing = true, isLoadingMore = false))
            scheduleRefreshIndicatorTimeout()
            manualRefreshRequested = true
            stopSubscriptions(clearRefreshing = false)
            startSubscriptions()
        }
    }

    fun startSubscriptions() {
        if (subscriptionsStarted) return
        subscriptionsStarted = true
        val ids = newSubscriptionIds()
        subscriptionIds = ids

        // 引用先イベント受信（nostr:note/nevent または q タグ）
        subscriptionJobs += launch {
            NostrRepository.events(ids.quote).collect { event ->
                if (event.kind != 1) return@collect
                val cur = currentFeedState()
                if (cur.quotedEvents.containsKey(event.id)) return@collect
                pendingQuoteIds.remove(event.id)
                setFeedState(cur.copy(quotedEvents = cur.quotedEvents + (event.id to event)), immediate = false)
                scheduleProfileFetch(event.pubkey)
            }
        }

        // ミュート・NGワード変更時にフィルタ済みリストを再構築
        if (filterMutedUsers) {
            subscriptionJobs += launch {
                accountSession?.muteStore?.mutedPubkeys?.collect { rebuildFilteredEvents() }
            }
        }
        subscriptionJobs += launch {
            accountSession?.ngWordStore?.ngWords?.collect { rebuildFilteredEvents() }
        }

        // content が空のリポストから元ポストを追加取得
        subscriptionJobs += launch {
            NostrRepository.events(ids.repostTarget).collect { event ->
                if (event.kind != 1) return@collect
                val pending = pendingRepostTargets.remove(event.id) ?: return@collect
                appendEvent(event, timelineCreatedAt = pending.repostedAt)
                markRepostedBy(event.id, pending.reposterPubkey)
                scheduleProfileFetch(event.pubkey)
                scheduleProfileFetch(pending.reposterPubkey)
                scheduleEngagementFetch(event.id)
            }
        }

        subscriptionJobs += launch {
            val current = currentFeedState()
            if (current.isInitialLoad && current.events.isEmpty() && !initialHistoryRequested) {
                // 初回のみ履歴ページを取得
                requestHistoryPage(until = null)
            } else if (manualRefreshRequested) {
                manualRefreshRequested = false
                requestHistoryPage(until = null)
                resubscribeEngagement()
            } else {
                // タブ再表示時はライブ購読を再開し、離れていた間のギャップを補完する
                val nowSec = Clock.System.now().epochSeconds
                subscribeLiveFeed(since = nowSec)
                val gapSince = eventSortTimes.values.maxOrNull()
                if (gapSince != null && gapSince < nowSec - 5) {
                    requestGapFill(since = gapSince, until = nowSec)
                }
                resubscribeEngagement()
            }
        }
    }

    fun stopSubscriptions(clearRefreshing: Boolean = true) {
        if (!subscriptionsStarted) return
        subscriptionsStarted = false
        val ids = subscriptionIds
        subscriptionIds = null
        subscriptionJobs.forEach { it.cancel() }
        subscriptionJobs.clear()
        historyCollectorJobs.forEach { it.cancel() }
        historyCollectorJobs.clear()
        engagementBatchJob?.cancel()
        if (clearRefreshing) {
            refreshIndicatorTimeoutJob?.cancel()
            refreshIndicatorTimeoutJob = null
        }
        emitFeedStateNow()
        if (loadingMore) {
            loadingMore = false
            val current = currentFeedState()
            setFeedState(current.copy(
                isInitialLoad = current.isInitialLoad && current.events.isEmpty(),
                canLoadMore = current.canLoadMore || current.events.isNotEmpty(),
                isLoadingMore = false,
                isRefreshing = if (clearRefreshing) false else current.isRefreshing,
            ))
        }
        if (currentFeedState().isInitialLoad && currentFeedState().events.isEmpty()) {
            initialHistoryRequested = false
        }
        val sessionsToClose = listOfNotNull(liveSession, engagementSession, currentHistorySession)
        liveSession = null
        engagementSession = null
        currentHistorySession = null
        if (sessionsToClose.isNotEmpty()) {
            launch { sessionsToClose.forEach { it.close() } }
        }
        ids?.let {
            NostrRepository.close(it.repostTarget)
            NostrRepository.close(it.quote)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSubscriptions()
    }

    private suspend fun requestHistoryPage(until: Long?) {
        val ids = subscriptionIds ?: return
        if (authorPubkeys?.isEmpty() == true) {
            loadingMore = false
            updateFeedState {
                it.copy(
                    isInitialLoad = false,
                    canLoadMore = false,
                    isLoadingMore = false,
                    isRefreshing = false,
                )
            }
            return
        }

        currentHistorySession?.close()
        val historySubId = nextHistorySubscriptionId(ids)

        isGapFill = false
        activeHistoryUntil = until
        loadingMore = true
        lastHistoryBatchReceivedCount = 0
        lastHistoryBatchUniqueCount = 0
        lastHistoryBatchOldestCreatedAt = null

        // 初回のみライブ購読も開始（since=現在時刻でライブイベントのみ）
        if (until == null) {
            initialHistoryRequested = true
            subscribeLiveFeed(since = Clock.System.now().epochSeconds)
        }
        updateFeedState { it.copy(canLoadMore = false, isLoadingMore = true) }
        val session = NostrRepository.openSubscription(
            SubscriptionSpec(
                id = historySubId,
                filters = listOf(
                    NostrFilter(
                        kinds = feedKinds(),
                        authors = authorPubkeys,
                        tTags = hashtag?.let { listOf(it) },
                        until = until,
                        limit = FEED_PAGE_SIZE,
                    ),
                ),
                target = relayTarget,
                behavior = SubscriptionBehavior.Fetch(HISTORY_FETCH_TIMEOUT_MS),
            ),
        )
        currentHistorySession = session
        startHistoryCollector(session)
    }

    private suspend fun requestGapFill(since: Long, until: Long) {
        val ids = subscriptionIds ?: return
        if (authorPubkeys?.isEmpty() == true) return
        currentHistorySession?.close()
        val historySubId = nextHistorySubscriptionId(ids)
        isGapFill = true
        loadingMore = true
        lastHistoryBatchReceivedCount = 0
        lastHistoryBatchUniqueCount = 0
        lastHistoryBatchOldestCreatedAt = null
        val session = NostrRepository.openSubscription(
            SubscriptionSpec(
                id = historySubId,
                filters = listOf(
                    NostrFilter(
                        kinds = feedKinds(),
                        authors = authorPubkeys,
                        tTags = hashtag?.let { listOf(it) },
                        since = since,
                        until = until,
                        limit = FEED_PAGE_SIZE,
                    ),
                ),
                target = relayTarget,
                behavior = SubscriptionBehavior.Fetch(HISTORY_FETCH_TIMEOUT_MS),
            ),
        )
        currentHistorySession = session
        startHistoryCollector(session)
    }

    private fun onHistoryPageCompleted() {
        if (!loadingMore) return
        loadingMore = false
        // リプライ等がフィルタされても受信件数が上限に達していれば次ページがある
        val hasMore = lastHistoryBatchReceivedCount >= FEED_PAGE_SIZE
        val oldestReceivedAt = lastHistoryBatchOldestCreatedAt
        val loadedVisibleEvents = lastHistoryBatchUniqueCount > 0
        if (!isGapFill) {
            shouldRetryHistoryPage = false
            nextHistoryUntil = if (hasMore && oldestReceivedAt != null) oldestReceivedAt - 1 else null
        }
        val cur = currentFeedState()
        // ギャップ補完は期間が限定されるため件数で過去ページの有無を判断できない
        setFeedState(cur.copy(
            canLoadMore = if (isGapFill) cur.canLoadMore else hasMore,
            isInitialLoad = false,
            isLoadingMore = false,
            isRefreshing = false,
        ))
        refreshIndicatorTimeoutJob?.cancel()
        refreshIndicatorTimeoutJob = null
        if (!isGapFill) {
            continuePastEmptyHistoryPageIfNeeded(
                hasMore = hasMore,
                loadedVisibleEvents = loadedVisibleEvents,
            )
        }
    }

    private fun onHistoryFetchIncomplete() {
        if (!loadingMore) return
        loadingMore = false
        val hasMore = lastHistoryBatchReceivedCount >= FEED_PAGE_SIZE
        val oldestReceivedAt = lastHistoryBatchOldestCreatedAt
        val loadedVisibleEvents = lastHistoryBatchUniqueCount > 0
        val canAdvanceFromPartialResponse = lastHistoryBatchReceivedCount > 0 && oldestReceivedAt != null
        val shouldRetryCurrentPage = !canAdvanceFromPartialResponse
        if (!isGapFill) {
            shouldRetryHistoryPage = shouldRetryCurrentPage
            nextHistoryUntil = if (shouldRetryCurrentPage) activeHistoryUntil else oldestReceivedAt - 1
        }
        val current = currentFeedState()
        setFeedState(current.copy(
            isInitialLoad = false,
            canLoadMore = if (isGapFill) {
                current.canLoadMore
            } else {
                hasMore || shouldRetryCurrentPage || canAdvanceFromPartialResponse
            },
            isLoadingMore = false,
            isRefreshing = false,
        ))
        refreshIndicatorTimeoutJob?.cancel()
        refreshIndicatorTimeoutJob = null
        if (!isGapFill && !shouldRetryCurrentPage) {
            continuePastEmptyHistoryPageIfNeeded(
                hasMore = hasMore || canAdvanceFromPartialResponse,
                loadedVisibleEvents = loadedVisibleEvents,
            )
        }
    }

    private fun continuePastEmptyHistoryPageIfNeeded(
        hasMore: Boolean,
        loadedVisibleEvents: Boolean,
    ) {
        if (loadedVisibleEvents) {
            consecutiveEmptyHistoryPages = 0
            return
        }
        if (!hasMore || nextHistoryUntil == null) {
            consecutiveEmptyHistoryPages = 0
            return
        }
        consecutiveEmptyHistoryPages++
        if (consecutiveEmptyHistoryPages > MAX_AUTO_SKIP_EMPTY_HISTORY_PAGES) return
        launch {
            requestHistoryPage(until = nextHistoryUntil)
        }
    }

    private fun startHistoryCollector(session: SubscriptionSession) {
        historyCollectorJobs.forEach { it.cancel() }
        historyCollectorJobs.clear()
        historyCollectorJobs += launch {
            session.signals.collect { signal ->
                if (currentHistorySession !== session) return@collect
                when (signal) {
                    is SubscriptionSignal.Event -> {
                        val event = signal.event
                        lastHistoryBatchReceivedCount++
                        lastHistoryBatchOldestCreatedAt = minOf(
                            lastHistoryBatchOldestCreatedAt ?: event.createdAt,
                            event.createdAt,
                        )
                        lastHistoryBatchUniqueCount += appendFeedEvent(event)
                        if (currentFeedState().isRefreshing && lastHistoryBatchUniqueCount > 0) {
                            clearRefreshIndicator()
                        }
                        if (currentFeedState().isInitialLoad && currentFeedState().events.isNotEmpty()) {
                            updateFeedState { it.copy(isInitialLoad = false) }
                        }
                    }
                    is SubscriptionSignal.FetchCompleted -> {
                        currentHistorySession = null
                        val incomplete = signal.timedOut ||
                            signal.outcomes.values.any { it !is RelayOutcome.Eose }
                        if (incomplete) onHistoryFetchIncomplete() else onHistoryPageCompleted()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun scheduleRefreshIndicatorTimeout() {
        refreshIndicatorTimeoutJob?.cancel()
        refreshIndicatorTimeoutJob = launch {
            delay(REFRESH_INDICATOR_TIMEOUT_MS)
            clearRefreshIndicator()
        }
    }

    private fun clearRefreshIndicator() {
        val current = currentFeedState()
        if (!current.isRefreshing) return
        setFeedState(current.copy(isRefreshing = false))
        refreshIndicatorTimeoutJob?.cancel()
        refreshIndicatorTimeoutJob = null
    }

    private suspend fun subscribeLiveFeed(since: Long) {
        val ids = subscriptionIds ?: return
        val filters = listOf(
            NostrFilter(
                kinds = feedKinds(),
                authors = authorPubkeys,
                tTags = hashtag?.let { listOf(it) },
                since = since,
            ),
        )
        val existing = liveSession
        if (existing != null) {
            existing.update(filters, relayTarget)
            return
        }

        val session = NostrRepository.openSubscription(
            SubscriptionSpec(
                id = ids.feed,
                filters = filters,
                target = relayTarget,
                behavior = SubscriptionBehavior.Live,
            ),
        )
        liveSession = session
        subscriptionJobs += launch {
            session.signals.collect { signal ->
                if (liveSession !== session) return@collect
                if (signal is SubscriptionSignal.Event) {
                    appendFeedEvent(signal.event)
                }
            }
        }
    }

    private fun feedKinds(): List<Int> = if (includeRepostsInFeed && hashtag == null) listOf(1, 6) else listOf(1)

    /** ポスト/リポストをフィード用に処理し、追加できた件数（0 or 1）を返す */
    private fun appendFeedEvent(event: NostrEvent): Int = when (event.kind) {
        1 -> {
            val parentId = event.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
            if (!includeRepliesInFeed && parentId != null) {
                scheduleEngagementFetch(parentId)
                0
            } else {
                val appended = appendEvent(event)
                if (appended > 0) {
                    scheduleProfileFetch(event.pubkey)
                    scheduleMentionedProfileFetch(event.content)
                    scheduleEngagementFetch(event.id)
                }
                appended
            }
        }
        6 -> appendRepostedEvent(event)
        else -> 0
    }

    /** イベントをリストに追加し、追加できた件数（0 or 1）を返す */
    private fun appendEvent(event: NostrEvent, timelineCreatedAt: Long = event.createdAt): Int {
        if (event.kind != 1) return 0
        if (!rememberSeenId(seenEventIds, event.id)) {
            updateTimelineSortTime(event.id, timelineCreatedAt)
            return 0
        }
        rawEvents[event.id] = event
        if (event.id !in canonicalEvents) {
            canonicalEvents[event.id] = event
        }
        eventSortTimes[event.id] = timelineCreatedAt
        while (rawEvents.size > MAX_SEEN_IDS) {
            val removedId = rawEvents.keys.first()
            rawEvents.remove(removedId)
            eventSortTimes.remove(removedId)
        }
        while (canonicalEvents.size > MAX_SEEN_IDS) canonicalEvents.remove(canonicalEvents.keys.first())
        if (oldestCreatedAt == null || timelineCreatedAt < (oldestCreatedAt ?: Long.MAX_VALUE)) {
            oldestCreatedAt = timelineCreatedAt
        }
        if (isFiltered(event)) return 0
        val cur = currentFeedState()
        if (cur.events.any { it.id == event.id }) return 0
        updateEvents(insertSorted(cur.events, event))
        val quoteIds = quotedEventIds(event)
        scheduleQuoteFetch(quoteIds)
        event.replyTargetId()?.takeIf { it !in quoteIds }?.let { scheduleQuoteFetch(listOf(it)) }
        return 1
    }

    private fun isFiltered(event: NostrEvent): Boolean {
        if (filterMutedUsers && accountSession?.muteStore?.isMuted(event.pubkey) == true) return true
        return accountSession?.ngWordStore?.matches(event.content) == true
    }

    private fun rebuildFilteredEvents() {
        val filtered = rawEvents.values
            .filter { !isFiltered(it) }
            .let(::sortTimelineEvents)
        updateEvents(filtered)
    }

    private fun appendRepostedEvent(repost: NostrEvent): Int {
        if (!includeRepostsInFeed || !rememberSeenId(seenRepostIds, repost.id)) return 0
        rememberReceivedEvent(receivedRepostEvents, repost)
        val targetId = repost.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
        val targetEvent = runCatching {
            Json.decodeFromString(NostrEvent.serializer(), repost.content)
        }.getOrNull()

        updateRepostState(repost, targetId ?: targetEvent?.id)

        if (targetEvent != null) {
            canonicalEvents[targetEvent.id] = targetEvent
            val appended = appendEvent(targetEvent, timelineCreatedAt = repost.createdAt)
            markRepostedBy(targetEvent.id, repost.pubkey)
            scheduleProfileFetch(targetEvent.pubkey)
            scheduleProfileFetch(repost.pubkey)
            scheduleMentionedProfileFetch(targetEvent.content)
            scheduleEngagementFetch(targetEvent.id)
            return appended
        }

        if (targetId != null) {
            val current = pendingRepostTargets[targetId]
            if (current == null || repost.createdAt > current.repostedAt) {
                pendingRepostTargets[targetId] = PendingRepostTarget(
                    repostedAt = repost.createdAt,
                    reposterPubkey = repost.pubkey,
                )
            }
            launch {
                val ids = subscriptionIds ?: return@launch
                NostrRepository.subscribe(
                    ids.repostTarget,
                    NostrFilter(ids = pendingRepostTargets.keys.toList(), kinds = listOf(1)),
                    target = relayTarget,
                )
            }
        }
        return 0
    }

    private fun updateRepostState(repost: NostrEvent, targetId: String?) {
        if (targetId == null) return
        val cur = currentFeedState()
        val isOwn = ownPubkey != null && repost.pubkey == ownPubkey
        setFeedState(
            EngagementAccumulator.repost(cur, targetId, repost, isOwn),
            immediate = false,
        )
    }

    private fun markRepostedBy(eventId: String, reposterPubkey: String) {
        val cur = currentFeedState()
        setFeedState(cur.copy(
            repostedByPubkeys = cur.repostedByPubkeys + (eventId to reposterPubkey),
        ), immediate = false)
    }

    private fun updateTimelineSortTime(eventId: String, timelineCreatedAt: Long) {
        val currentSortTime = eventSortTimes[eventId]
        if (currentSortTime != null && timelineCreatedAt <= currentSortTime) return
        eventSortTimes[eventId] = timelineCreatedAt
        val cur = currentFeedState()
        if (cur.events.none { it.id == eventId }) return
        updateEvents(sortTimelineEvents(cur.events))
    }

    private fun sortTimelineEvents(events: List<NostrEvent>): List<NostrEvent> =
        events.sortedByDescending { eventSortTimes[it.id] ?: it.createdAt }

    private fun insertSorted(events: List<NostrEvent>, event: NostrEvent): List<NostrEvent> {
        val sortTime = eventSortTimes[event.id] ?: event.createdAt
        var lo = 0
        var hi = events.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if ((eventSortTimes[events[mid].id] ?: events[mid].createdAt) > sortTime) lo = mid + 1
            else hi = mid
        }
        return events.take(lo) + event + events.drop(lo)
    }

    private fun updateEvents(events: List<NostrEvent>, immediate: Boolean = false) {
        val visibleEvents = events.take(MAX_TIMELINE_EVENTS)
        val visibleEventIds = visibleEvents.mapTo(linkedSetOf()) { it.id }
        val retainedEventIds = visibleEventIds + visibleEvents.mapNotNull { it.replyTargetId() } +
            visibleEvents.flatMap { quotedEventIds(it) }
        val current = currentFeedState()
        val quotedEvents = current.quotedEvents.filterKeys { it in retainedEventIds }
        val replies = current.replies.filterKeys { it in visibleEventIds }
        val retainedPubkeys = buildSet {
            visibleEvents.forEach { event ->
                add(event.pubkey)
                extractNpubReferences(event.content).forEach { add(it.pubkey) }
            }
            quotedEvents.values.forEach { event ->
                add(event.pubkey)
                extractNpubReferences(event.content).forEach { add(it.pubkey) }
            }
            replies.values.flatten().forEach { event ->
                add(event.pubkey)
                extractNpubReferences(event.content).forEach { add(it.pubkey) }
            }
            current.repostedByPubkeys.forEach { (eventId, pubkey) ->
                if (eventId in visibleEventIds) add(pubkey)
            }
            ownPubkey?.let(::add)
        }
        val profiles = current.profiles.filterKeys { it in retainedPubkeys } +
            ProfileRepository.getCached(retainedPubkeys)

        setFeedState(current.copy(
            events = visibleEvents,
            profiles = profiles,
            reactionCounts = current.reactionCounts.filterKeys { it in retainedEventIds },
            likeReactionCounts = current.likeReactionCounts.filterKeys { it in retainedEventIds },
            customReactions = current.customReactions.filterKeys { it in retainedEventIds },
            unicodeReactions = current.unicodeReactions.filterKeys { it in retainedEventIds },
            replyCounts = current.replyCounts.filterKeys { it in retainedEventIds },
            replies = replies,
            repostCounts = current.repostCounts.filterKeys { it in retainedEventIds },
            quotedEvents = quotedEvents,
            repostedByPubkeys = current.repostedByPubkeys.filterKeys { it in visibleEventIds },
            likedReactions = current.likedReactions.filterKeys { it in retainedEventIds },
            ownEmojiReactionEventIds = current.ownEmojiReactionEventIds
                .filterKeys { it in retainedEventIds },
            repostedEvents = current.repostedEvents.filterKeys { it in retainedEventIds },
        ), immediate = immediate)
    }

    private fun rememberSeenId(seenIds: LinkedHashSet<String>, eventId: String): Boolean {
        if (!seenIds.add(eventId)) return false
        while (seenIds.size > MAX_SEEN_IDS) seenIds.remove(seenIds.first())
        return true
    }

    private fun rememberReceivedEvent(events: LinkedHashMap<String, NostrEvent>, event: NostrEvent) {
        events[event.id] = event
        while (events.size > MAX_SEEN_IDS) events.remove(events.keys.first())
    }

    private fun reconcileOwnEngagement() {
        val pubkey = ownPubkey ?: return
        setFeedState(
            EngagementAccumulator.reconcileOwnEngagement(
                state = currentFeedState(),
                ownPubkey = pubkey,
                reactionEvents = receivedReactionEvents.values,
                repostEvents = receivedRepostEvents.values,
            ),
        )
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in currentFeedState().profiles) return
        ProfileRepository.getCached(pubkey)?.let { cachedProfile ->
            updateFeedState(immediate = false) { state ->
                state.copy(profiles = state.profiles + (pubkey to cachedProfile))
            }
            return
        }
        if (!requestedProfilePubkeys.add(pubkey)) return
        launch {
            ProfileRepository.ensureProfiles(
                pubkeys = setOf(pubkey),
                policy = ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS),
                relayHint = relayUrl,
            )
        }
    }

    private fun scheduleMentionedProfileFetch(text: String) {
        extractNpubReferences(text).forEach { reference ->
            scheduleProfileFetch(reference.pubkey)
        }
    }

    private suspend fun resubscribeEngagement() {
        val subIds = subscriptionIds ?: return
        if (watchedEventIds.isEmpty()) return
        val ids = watchedEventIds.toList()
        val filters = engagementFilters(ids)
        val existing = engagementSession
        if (existing != null) {
            existing.update(filters, relayTarget)
            return
        }

        val session = NostrRepository.openSubscription(
            SubscriptionSpec(
                id = subIds.reaction,
                filters = filters,
                target = relayTarget,
                behavior = SubscriptionBehavior.Live,
            ),
        )
        engagementSession = session
        subscriptionJobs += launch {
            session.signals.collect { signal ->
                if (engagementSession !== session) return@collect
                if (signal is SubscriptionSignal.Event) {
                    handleEngagementEvent(signal.event)
                }
            }
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
            resubscribeEngagement()
        }
    }

    private fun engagementFilters(ids: List<String>): List<NostrFilter> = listOf(
        NostrFilter(kinds = listOf(7), eTags = ids),
        NostrFilter(kinds = listOf(1), eTags = ids),
        NostrFilter(kinds = listOf(6), eTags = ids),
        NostrFilter(kinds = listOf(1), qTags = ids),
    )

    private fun handleEngagementEvent(event: NostrEvent) {
        when (event.kind) {
            7 -> handleReactionEvent(event)
            6 -> handleEngagementRepostEvent(event)
            1 -> {
                handleReplyEvent(event)
                handleQuoteRepostEvent(event)
            }
        }
    }

    private fun handleReactionEvent(event: NostrEvent) {
        if (!rememberSeenId(seenReactionIds, event.id)) return
        val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
            ?.takeIf { it in watchedEventIds }
            ?: return
        rememberReceivedEvent(receivedReactionEvents, event)
        val cur = currentFeedState()
        val isOwn = ownPubkey != null && event.pubkey == ownPubkey
        setFeedState(
            EngagementAccumulator.reaction(cur, targetId, event, isOwn),
            immediate = false,
        )
    }

    private fun handleReplyEvent(event: NostrEvent) {
        val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
            ?.takeIf { it in watchedEventIds }
            ?: return
        if (!rememberSeenId(seenReplyIds, event.id)) return
        scheduleProfileFetch(event.pubkey)
        scheduleMentionedProfileFetch(event.content)
        setFeedState(
            EngagementAccumulator.reply(currentFeedState(), targetId, event),
            immediate = false,
        )
    }

    private fun handleEngagementRepostEvent(event: NostrEvent) {
        val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
            ?.takeIf { it in watchedEventIds }
            ?: return
        if (!rememberSeenId(seenRepostIds, event.id)) return
        rememberReceivedEvent(receivedRepostEvents, event)
        val cur = currentFeedState()
        val isOwn = ownPubkey != null && event.pubkey == ownPubkey
        setFeedState(
            EngagementAccumulator.repost(cur, targetId, event, isOwn),
            immediate = false,
        )
    }

    private fun handleQuoteRepostEvent(event: NostrEvent) {
        val targetIds = event.tags
            .filter { it.firstOrNull() == "q" }
            .mapNotNull { it.getOrNull(1) }
            .distinct()
            .filter { it in watchedEventIds }
            .filter { rememberSeenId(seenQuoteRepostIds, "${event.id}:$it") }
        if (targetIds.isEmpty()) return
        setFeedState(
            EngagementAccumulator.quoteReposts(currentFeedState(), targetIds),
            immediate = false,
        )
    }

    private fun scheduleQuoteFetch(eventIds: List<String>) {
        val missingIds = eventIds.filter { id ->
            id !in currentFeedState().quotedEvents && pendingQuoteIds.add(id)
        }
        if (missingIds.isEmpty()) return
        launch {
            val ids = subscriptionIds ?: return@launch
            NostrRepository.subscribe(
                ids.quote,
                NostrFilter(ids = pendingQuoteIds.toList(), kinds = listOf(1)),
                target = relayTarget,
            )
        }
    }

    private fun newSubscriptionIds(): SubscriptionIds {
        subscriptionGeneration++
        val suffix = "$shortKey-$instanceKey-$subscriptionGeneration"
        return SubscriptionIds(
            feed = "feed-$suffix",
            history = "hist-$suffix",
            reaction = "reac-$suffix",
            repostTarget = "rpt-$suffix",
            quote = "quot-$suffix",
        )
    }

    private fun nextHistorySubscriptionId(ids: SubscriptionIds): String {
        historyRequestGeneration++
        return "${ids.history}-$historyRequestGeneration"
    }

    companion object {
        private const val FEED_PAGE_SIZE = 30
        private const val MAX_TIMELINE_EVENTS = 800
        private const val MAX_TRACKED_ENGAGEMENT_EVENTS = 100
        private const val MAX_SEEN_IDS = 2000
        private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
        private const val FEED_STATE_EMIT_DELAY_MS = 150L
        private const val REFRESH_INDICATOR_TIMEOUT_MS = 2_500L
        private const val HISTORY_FETCH_TIMEOUT_MS = 10_000L
        private const val MAX_AUTO_SKIP_EMPTY_HISTORY_PAGES = 5
        private var nextInstanceKeyValue = 0

        private fun nextInstanceKey(): Int = ++nextInstanceKeyValue
    }
}

private fun FeedViewModel.UiState.noteEngagement(eventId: String): NoteEngagementState = NoteEngagementState(
    reactionCount = reactionCounts[eventId] ?: 0,
    likeReactionCount = likeReactionCounts[eventId] ?: 0,
    customReactions = customReactions[eventId].orEmpty(),
    unicodeReactions = unicodeReactions[eventId].orEmpty(),
    ownLikeEventId = likedReactions[eventId],
    ownEmojiReactionEventIds = ownEmojiReactionEventIds[eventId].orEmpty(),
    repostCount = repostCounts[eventId] ?: 0,
    ownRepostEventId = repostedEvents[eventId],
    pendingOperations = pendingEngagementOperations[eventId].orEmpty(),
)

private fun FeedViewModel.UiState.withEngagement(
    eventId: String,
    engagement: NoteEngagementState,
): FeedViewModel.UiState = copy(
    reactionCounts = reactionCounts + (eventId to engagement.reactionCount),
    likeReactionCounts = likeReactionCounts + (eventId to engagement.likeReactionCount),
    customReactions = customReactions.putListOrRemove(eventId, engagement.customReactions),
    unicodeReactions = unicodeReactions.putListOrRemove(eventId, engagement.unicodeReactions),
    likedReactions = likedReactions.putOrRemove(eventId, engagement.ownLikeEventId),
    ownEmojiReactionEventIds = ownEmojiReactionEventIds.putMapOrRemove(
        eventId,
        engagement.ownEmojiReactionEventIds,
    ),
    repostCounts = repostCounts + (eventId to engagement.repostCount),
    repostedEvents = repostedEvents.putOrRemove(eventId, engagement.ownRepostEventId),
    pendingEngagementOperations = pendingEngagementOperations.putMapOrRemove(
        eventId,
        engagement.pendingOperations,
    ),
)

private fun <K, V> Map<K, V>.putOrRemove(key: K, value: V?): Map<K, V> =
    if (value == null) this - key else this + (key to value)

private fun <K, V> Map<K, List<V>>.putListOrRemove(key: K, value: List<V>): Map<K, List<V>> =
    if (value.isEmpty()) this - key else this + (key to value)

private fun <K, K2, V2> Map<K, Map<K2, V2>>.putMapOrRemove(
    key: K,
    value: Map<K2, V2>,
): Map<K, Map<K2, V2>> = if (value.isEmpty()) this - key else this + (key to value)

private data class PendingRepostTarget(
    val repostedAt: Long,
    val reposterPubkey: String,
)

private data class SubscriptionIds(
    val feed: String,
    val history: String,
    val reaction: String,
    val repostTarget: String,
    val quote: String,
)
