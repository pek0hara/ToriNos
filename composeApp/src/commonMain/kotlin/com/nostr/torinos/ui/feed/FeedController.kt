package com.nostr.torinos.ui.feed

import com.nostr.torinos.ui.feed.FeedViewModel.UiState
import com.nostr.torinos.ui.feed.FeedViewModel.InitialFeedState
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.engagement.EngagementAction
import com.nostr.torinos.engagement.EngagementOperationId
import com.nostr.torinos.engagement.EngagementReducer
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.NoteEngagementCommand
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.engagement.ReactionEventReducer
import com.nostr.torinos.engagement.ReactionRefreshFetcher
import com.nostr.torinos.engagement.NoteTarget
import com.nostr.torinos.engagement.PendingEngagementOperation
import com.nostr.torinos.engagement.displayOwnEmojiReactionEventIds
import com.nostr.torinos.engagement.isRepostedByMe
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.isSupportedTimelineComment
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RetryDisposition
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.ui.SafeCoroutineLauncher
import com.nostr.torinos.ui.timeline.NoteEngagementCoordinator
import com.nostr.torinos.ui.timeline.NoteCardSync
import com.nostr.torinos.ui.timeline.NoteCardSnapshot
import com.nostr.torinos.ui.timeline.NoteDeletionSync
import com.nostr.torinos.ui.timeline.NoteDeletionResult
import com.nostr.torinos.ui.timeline.NoteDeletionService
import com.nostr.torinos.ui.timeline.StateStore
import com.nostr.torinos.ui.timeline.SignedEventPublisher
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface FeedSubscriptionGateway {
    val readableRelayUrls: Flow<Set<String>>

    fun events(subscriptionId: String): Flow<NostrEvent>

    suspend fun targetRelayUrls(target: RelayTarget): Set<String>

    suspend fun open(spec: SubscriptionSpec): SubscriptionSession

    suspend fun subscribe(
        subscriptionId: String,
        filter: NostrFilter,
        target: RelayTarget,
    ): Set<String>

    fun close(subscriptionId: String)
}

private object RepositoryFeedSubscriptionGateway : FeedSubscriptionGateway {
    override val readableRelayUrls: Flow<Set<String>> =
        NostrRepository.routingRelayUrls

    override fun events(subscriptionId: String): Flow<NostrEvent> =
        NostrRepository.events(subscriptionId)

    override suspend fun targetRelayUrls(target: RelayTarget): Set<String> =
        NostrRepository.targetRelayUrls(target)

    override suspend fun open(spec: SubscriptionSpec): SubscriptionSession =
        NostrRepository.openSubscription(spec)

    override suspend fun subscribe(
        subscriptionId: String,
        filter: NostrFilter,
        target: RelayTarget,
    ): Set<String> = NostrRepository.subscribe(subscriptionId, filter, target)

    override fun close(subscriptionId: String) {
        NostrRepository.close(subscriptionId)
    }
}

internal class FeedController(
    private val accountSession: AccountSession? = null,
    private val authorPubkey: String? = null,
    private val authorPubkeys: List<String>? = authorPubkey?.let { listOf(it) },
    private val relayUrl: String? = null,
    private val autoStart: Boolean = true,
    private val includeRepostsInFeed: Boolean = false,
    private val includeRepliesInFeed: Boolean = false,
    private val hashtag: String? = null,
    private val filterMutedUsers: Boolean = true,
    private val scope: CoroutineScope,
    private val subscriptions: FeedSubscriptionGateway = RepositoryFeedSubscriptionGateway,
    private val feedEventKinds: Set<Int> = setOf(1),
) {
    private val safeCoroutineLauncher = SafeCoroutineLauncher(scope, "FeedController")
    private fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        safeCoroutineLauncher.launch(block = block)

    private val _state = StateStore(UiState())
    val state: StateFlow<UiState> = _state.state
    private var pendingFeedState = UiState()
    private var feedStateEmitJob: Job? = null
    private val pendingTimelineEvents = linkedMapOf<String, NostrEvent>()
    private var timelineBatchJob: Job? = null
    private var closed = false

    private val instanceKey = nextInstanceKey()
    private val shortKey = authorPubkey?.take(16) ?: authorPubkeys?.hashCode()?.toString() ?: "global"
    private val subscriptionJobs = mutableListOf<Job>()
    private val lifecycleJobs = mutableListOf<Job>()
    private val historyCollectorJobs = mutableListOf<Job>()
    private var subscriptionIds: SubscriptionIds? = null
    private var liveSession: SubscriptionSession? = null
    private var engagementSession: SubscriptionSession? = null
    private var engagementHistorySession: SubscriptionSession? = null
    private var currentHistorySession: SubscriptionSession? = null
    private var relayHistoryCoordinator: RelayFeedHistoryCoordinator? = null
    private var historyIndicatorSettleJob: Job? = null
    private var subscriptionGeneration = 0
    private var historyRequestGeneration = 0
    private val requestedProfilePubkeys = mutableSetOf<String>()
    private var refreshIndicatorTimeoutJob: Job? = null
    private var initialFeedSlowJob: Job? = null
    private val watchedEventIds = linkedSetOf<String>()
    private val engagementHistoryStates = mutableMapOf<String, EngagementHistoryState>()
    private val engagementCompletedPartitions =
        mutableMapOf<String, MutableMap<String, MutableSet<EngagementPartition>>>()
    private val isolatedEngagementRelays = mutableSetOf<String>()
    private val engagementFailureCounts = mutableMapOf<String, Int>()
    private val engagementEventMutex = Mutex()
    private val engagementSubscriptionMutex = Mutex()
    private var engagementHistoryDedup: EngagementHistoryDedup? = null
    private var lastEngagementTargetRelays: Set<String>? = null
    private var engagementBatchJob: Job? = null
    private val engagementRetryJobs = mutableMapOf<String, Job>()
    private var engagementResumeSince: Long? = null
    private var nextEngagementHistoryRequestId = 0L
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
    private var handledLatestResetRequest = 0
    private var ownPubkey: String? = null
    private val engagementCoordinator = NoteEngagementCoordinator(accountSession?.signer)
    private val signedEventPublisher = SignedEventPublisher(accountSession?.signer)
    private val noteDeletionService = NoteDeletionService(accountSession?.signer, accountSession?.sessionId)
    private var nextEngagementOperationId = 0L
    private var nextReactionRefreshId = 0L

    private var oldestCreatedAt: Long? = null
    private var nextHistoryUntil: Long? = null
    private var activeHistoryUntil: Long? = null
    private var nextHistoryPageSize = FEED_PAGE_SIZE
    private var activeHistoryPageSize = FEED_PAGE_SIZE
    private var shouldRetryHistoryPage = false
    private var loadingMore = false
    private var isGapFill = false
    private var lastHistoryBatchUniqueCount = 0
    private val historyPageCreatedAtByEventId = linkedMapOf<String, Long>()
    private val historyPageEventIdsByRelay = mutableMapOf<String, MutableSet<String>>()
    private val pendingHistoryRelayUrls = mutableSetOf<String>()
    private var historyRevealOldestAt: Long? = null
    private var consecutiveEmptyHistoryPages = 0
    private var initialHistoryRequested = false
    private var manualRefreshRequested = false
    private val relayTarget: RelayTarget = relayUrl?.let(RelayTarget::Single) ?: RelayTarget.AllEnabled

    init {
        if (isWriteSupported) {
            lifecycleJobs += launch {
                ownPubkey = accountSession?.pubkey
                reconcileOwnEngagement()
            }
        }
        lifecycleJobs += launch {
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
        lifecycleJobs += launch {
            NoteCardSync.updates.collect { snapshot ->
                if (snapshot.sessionId != accountSession?.sessionId) return@collect
                if (snapshot.eventId !in watchedEventIds) return@collect
                updateFeedState { it.withCardSnapshot(snapshot) }
            }
        }
        lifecycleJobs += launch {
            NoteDeletionSync.updates.collect { deletion ->
                if (deletion.sessionId != accountSession?.sessionId) return@collect
                removeEventLocally(deletion.eventId)
            }
        }
        lifecycleJobs += launch {
            subscriptions.readableRelayUrls.collect {
                val targetRelays = subscriptions.targetRelayUrls(relayTarget)
                relayHistoryCoordinator?.updateRelays(targetRelays)
                val previousRelays = lastEngagementTargetRelays
                lastEngagementTargetRelays = targetRelays
                if (previousRelays == null || previousRelays == targetRelays) return@collect

                val removedRelays = previousRelays - targetRelays
                if (removedRelays.isNotEmpty()) {
                    engagementCompletedPartitions.values.forEach { completedByRelay ->
                        removedRelays.forEach(completedByRelay::remove)
                    }
                    isolatedEngagementRelays.removeAll(removedRelays)
                    removedRelays.forEach { relayUrl ->
                        engagementFailureCounts.remove(relayUrl)
                        engagementRetryJobs.remove(relayUrl)?.cancel()
                    }
                }
                if (
                    subscriptionsStarted &&
                    watchedEventIds.isNotEmpty()
                ) {
                    resubscribeEngagement()
                }
            }
        }
        if (autoStart) startSubscriptions()
    }

    private fun currentFeedState(): UiState = pendingFeedState

    private fun setFeedState(value: UiState, immediate: Boolean = true) {
        if (closed) return
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
        if (closed) return
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
        if (closed) return
        _state.value = pendingFeedState
    }

    fun injectProfile(pubkey: String, profile: com.nostr.torinos.model.NostrProfile) {
        ProfileRepository.applyOptimistic(pubkey, profile)
        val currentProfile = ProfileRepository.getCached(pubkey) ?: profile
        if (currentFeedState().profiles[pubkey] == currentProfile) return
        updateFeedState { it.copy(profiles = it.profiles + (pubkey to currentProfile)) }
    }

    fun deleteEvent(eventId: String) {
        val event = currentFeedState().events.firstOrNull { it.id == eventId }
            ?: canonicalEvents[eventId]
            ?: return
        launch {
            when (val result = noteDeletionService.delete(event)) {
                NoteDeletionResult.Deleted -> removeEventLocally(eventId)
                NoteDeletionResult.MissingSigner -> updateFeedState {
                    it.copy(engagementError = "秘密鍵が設定されていません")
                }
                NoteDeletionResult.NotOwner -> updateFeedState {
                    it.copy(engagementError = "自分の投稿だけ削除できます")
                }
                is NoteDeletionResult.Failed -> updateFeedState {
                    it.copy(engagementError = result.cause.message ?: "投稿の削除要求を送信できませんでした")
                }
            }
        }
    }

    private fun removeEventLocally(eventId: String) {
        pendingTimelineEvents.remove(eventId)
        val cur = currentFeedState()
        updateEvents(cur.events.filter { it.id != eventId }, immediate = true)
        seenEventIds.remove(eventId)
        rawEvents.remove(eventId)
        canonicalEvents.remove(eventId)
        eventSortTimes.remove(eventId)
        forgetEngagementHistory(eventId)
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
        val optimistic = engagementCoordinator.begin(before, operationId, request)
        if (optimistic == before) return
        updateFeedState { it.withEngagement(eventId, optimistic).copy(engagementError = null) }
        launch {
            var committed = false
            var failure: Throwable? = null
            var signedEvent: NostrEvent? = null
            try {
                val published = engagementCoordinator.execute(command) { signed ->
                    signedEvent = signed
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
                    var next = it.withEngagement(
                        eventId,
                        engagementCoordinator.commit(current, operationId, published.id),
                    )
                    next = when (command) {
                        is NoteEngagementCommand.AddLike,
                        is NoteEngagementCommand.AddEmoji,
                        -> signedEvent?.let { reaction ->
                            next.copy(
                                reactionEvents = next.reactionEvents + (
                                    eventId to ReactionEventReducer.add(
                                        next.reactionEvents[eventId].orEmpty(),
                                        reaction,
                                    )
                                ),
                            )
                        } ?: next
                        is NoteEngagementCommand.RemoveReaction -> next.copy(
                            reactionEvents = next.reactionEvents + (
                                eventId to ReactionEventReducer.remove(
                                    next.reactionEvents[eventId].orEmpty(),
                                    command.reactionEventId,
                                )
                            ),
                        )
                        is NoteEngagementCommand.AddRepost,
                        is NoteEngagementCommand.RemoveRepost,
                        -> next
                    }
                    next
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
                            engagementCoordinator.rollback(current, operationId),
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
            signedEventPublisher.publish(
                detail,
                1984,
                listOf(listOf("e", event.id, "", reason), listOf("p", event.pubkey)),
            )
        }
    }

    fun loadMore() {
        relayHistoryCoordinator?.let { coordinator ->
            if (currentFeedState().canLoadMore) coordinator.loadMore()
            return
        }
        if (loadingMore || !currentFeedState().canLoadMore) return
        launch {
            val until = if (shouldRetryHistoryPage) nextHistoryUntil else nextHistoryUntil ?: oldestCreatedAt?.minus(1)
            requestHistoryPage(
                until = until,
                retryRelayUrls = pendingHistoryRelayUrls.takeIf { shouldRetryHistoryPage }?.toSet(),
            )
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

    fun refreshReactions(eventId: String) {
        if (eventId !in engagementHistoryStates) return
        launch {
            val events = ReactionRefreshFetcher.fetch(
                subscriptionId = "feed-reaction-refresh-$instanceKey-${++nextReactionRefreshId}",
                eventId = eventId,
                target = relayTarget,
            )
            events.forEach { event ->
                engagementEventMutex.withLock { handleReactionEvent(event) }
            }
        }
    }

    /** 長時間のバックグラウンド復帰時に、フィード固有のメモリ状態だけを破棄する。 */
    fun resetToLatest(request: Int): Boolean {
        if (closed) return false
        if (request <= 0 || request <= handledLatestResetRequest) return false
        handledLatestResetRequest = request

        stopSubscriptions()
        timelineBatchJob?.cancel()
        timelineBatchJob = null
        feedStateEmitJob?.cancel()
        feedStateEmitJob = null
        refreshIndicatorTimeoutJob?.cancel()
        refreshIndicatorTimeoutJob = null
        initialFeedSlowJob?.cancel()
        initialFeedSlowJob = null
        historyIndicatorSettleJob?.cancel()
        historyIndicatorSettleJob = null
        engagementBatchJob?.cancel()
        engagementBatchJob = null
        engagementRetryJobs.values.forEach(Job::cancel)
        engagementRetryJobs.clear()

        pendingTimelineEvents.clear()
        requestedProfilePubkeys.clear()
        watchedEventIds.clear()
        engagementHistoryStates.clear()
        engagementCompletedPartitions.clear()
        isolatedEngagementRelays.clear()
        engagementFailureCounts.clear()
        engagementHistoryDedup = null
        lastEngagementTargetRelays = null
        engagementResumeSince = null
        seenReactionIds.clear()
        seenReplyIds.clear()
        seenRepostIds.clear()
        seenQuoteRepostIds.clear()
        seenEventIds.clear()
        receivedReactionEvents.clear()
        receivedRepostEvents.clear()
        rawEvents.clear()
        canonicalEvents.clear()
        eventSortTimes.clear()
        pendingQuoteIds.clear()
        pendingRepostTargets.clear()

        relayHistoryCoordinator?.close()
        relayHistoryCoordinator = null

        oldestCreatedAt = null
        nextHistoryUntil = null
        activeHistoryUntil = null
        nextHistoryPageSize = FEED_PAGE_SIZE
        activeHistoryPageSize = FEED_PAGE_SIZE
        shouldRetryHistoryPage = false
        loadingMore = false
        isGapFill = false
        lastHistoryBatchUniqueCount = 0
        historyPageCreatedAtByEventId.clear()
        historyPageEventIdsByRelay.clear()
        pendingHistoryRelayUrls.clear()
        historyRevealOldestAt = null
        consecutiveEmptyHistoryPages = 0
        initialHistoryRequested = false
        manualRefreshRequested = false
        pendingFeedState = UiState()
        _state.value = pendingFeedState
        return true
    }

    fun startSubscriptions() {
        if (closed || subscriptionsStarted) return
        subscriptionsStarted = true
        if (pendingTimelineEvents.isNotEmpty()) {
            timelineBatchJob?.cancel()
            timelineBatchJob = null
            scheduleTimelineBatch()
        }
        val ids = newSubscriptionIds()
        subscriptionIds = ids

        // 引用先イベント受信（nostr:note/nevent または q タグ）
        subscriptionJobs += launch {
            subscriptions.events(ids.quote).collect { event ->
                if (event.kind !in DISPLAY_EVENT_KINDS) return@collect
                if (event.kind == COMMENT_EVENT_KIND && !event.isSupportedTimelineComment()) return@collect
                val cur = currentFeedState()
                if (cur.quotedEvents.containsKey(event.id)) return@collect
                pendingQuoteIds.remove(event.id)
                setFeedState(cur.copy(quotedEvents = cur.quotedEvents + (event.id to event)), immediate = false)
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
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
            subscriptions.events(ids.repostTarget).collect { event ->
                if (event.kind !in DISPLAY_EVENT_KINDS) return@collect
                if (event.kind == COMMENT_EVENT_KIND && !event.isSupportedTimelineComment()) return@collect
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
                startRelayHistory()
            } else if (manualRefreshRequested) {
                manualRefreshRequested = false
                startRelayHistory()
                resubscribeEngagement(retryPartialHistory = true)
            } else {
                // タブ再表示時はライブ購読を再開し、離れていた間のギャップを補完する
                val nowSec = Clock.System.now().epochSeconds
                subscribeLiveFeed(since = nowSec)
                val gapSince = eventSortTimes.values.maxOrNull()
                when (resumeSyncStrategy(gapSince = gapSince, nowSec = nowSec)) {
                    ResumeSyncStrategy.None -> Unit
                    ResumeSyncStrategy.GapFill -> requestBackgroundSync(
                        since = gapSince,
                        until = nowSec,
                    )
                    ResumeSyncStrategy.LatestPage -> requestBackgroundSync(
                        since = null,
                        until = nowSec,
                    )
                }
                resubscribeEngagement(retryPartialHistory = true)
            }
        }
    }

    fun stopSubscriptions(clearRefreshing: Boolean = true) {
        if (!subscriptionsStarted) return
        subscriptionsStarted = false
        if (engagementSession != null) {
            engagementResumeSince = (
                Clock.System.now().epochSeconds - ENGAGEMENT_LIVE_OVERLAP_SECONDS
            ).coerceAtLeast(0L)
        }
        engagementHistoryStates.keys.toList().forEach { eventId ->
            if (engagementHistoryStates[eventId] == EngagementHistoryState.InFlight) {
                engagementHistoryStates[eventId] = EngagementHistoryState.Partial
            }
        }
        val ids = subscriptionIds
        subscriptionIds = null
        subscriptionJobs.forEach { it.cancel() }
        subscriptionJobs.clear()
        historyCollectorJobs.forEach { it.cancel() }
        historyCollectorJobs.clear()
        historyIndicatorSettleJob?.cancel()
        historyIndicatorSettleJob = null
        initialFeedSlowJob?.cancel()
        initialFeedSlowJob = null
        relayHistoryCoordinator?.close()
        relayHistoryCoordinator = null
        engagementBatchJob?.cancel()
        engagementRetryJobs.values.forEach(Job::cancel)
        engagementRetryJobs.clear()
        timelineBatchJob?.cancel()
        timelineBatchJob = null
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
        val sessionsToClose = listOfNotNull(
            liveSession,
            engagementSession,
            engagementHistorySession,
            currentHistorySession,
        )
        liveSession = null
        engagementSession = null
        engagementHistorySession = null
        engagementHistoryDedup = null
        currentHistorySession = null
        if (sessionsToClose.isNotEmpty()) {
            launch { sessionsToClose.forEach { it.close() } }
        }
        ids?.let {
            subscriptions.close(it.repostTarget)
            subscriptions.close(it.quote)
        }
    }

    fun close() {
        if (closed) return
        stopSubscriptions()
        closed = true
        timelineBatchJob?.cancel()
        timelineBatchJob = null
        feedStateEmitJob?.cancel()
        feedStateEmitJob = null
        initialFeedSlowJob?.cancel()
        initialFeedSlowJob = null
        pendingTimelineEvents.clear()
        lifecycleJobs.forEach { it.cancel() }
        lifecycleJobs.clear()
    }

    /** 初回・手動更新の履歴を、停止リレーが他を塞がない独立セッションで取得する。 */
    private suspend fun startRelayHistory() {
        if (authorPubkeys?.isEmpty() == true) {
            updateFeedState {
                it.copy(
                    isInitialLoad = false,
                    initialFeedState = InitialFeedState.Empty,
                    canLoadMore = false,
                    isLoadingMore = false,
                    isRefreshing = false,
                )
            }
            return
        }

        relayHistoryCoordinator?.close()
        val historyFloor = Clock.System.now().epochSeconds
        initialHistoryRequested = true
        subscribeLiveFeed(since = historyFloor)
        val targetRelays = subscriptions.targetRelayUrls(relayTarget)
        scheduleInitialFeedSlowState()
        val coordinator = RelayFeedHistoryCoordinator(
            scope = scope,
            subscriptions = subscriptions,
            idPrefix = "feed-$instanceKey-${subscriptionGeneration}",
            baseFilters = feedFilters(),
            historyFloor = historyFloor,
            fetchTimeoutMillis = HISTORY_FETCH_TIMEOUT_MS,
            pageSize = FEED_PAGE_SIZE,
            maxPageSize = MAX_HISTORY_PAGE_SIZE,
            settleDelayMillis = HISTORY_RELAY_SETTLE_DELAY_MS,
            onEvent = { event ->
                val added = appendFeedEvent(event)
                if (currentFeedState().isRefreshing && added > 0) clearRefreshIndicator()
                added
            },
            onPageBoundary = { oldest ->
                revealHistoryThrough(oldest)
                flushPendingTimelineEvents()
            },
            onState = { history ->
                historyRequestGeneration = history.generation
                val current = currentFeedState()
                val initialFeedState = when {
                    current.events.isNotEmpty() -> InitialFeedState.ContentReady
                    history.isInitialFetchSettled && history.successfulInitialRelayCount > 0 ->
                        InitialFeedState.Empty
                    history.isInitialFetchSettled && history.relayCount > 0 ->
                        InitialFeedState.Failed
                    current.initialFeedState == InitialFeedState.Slow -> InitialFeedState.Slow
                    else -> InitialFeedState.Loading
                }
                updateFeedState {
                    it.copy(
                        isInitialLoad = initialFeedState == InitialFeedState.Loading ||
                            initialFeedState == InitialFeedState.Slow,
                        initialFeedState = initialFeedState,
                        canLoadMore = history.canLoadMore,
                        isLoadingMore = history.isLoading,
                        isRefreshing = current.isRefreshing && history.isLoading,
                        historyRequestGeneration = history.generation,
                    )
                }
                if (!history.isLoading) {
                    refreshIndicatorTimeoutJob?.cancel()
                    refreshIndicatorTimeoutJob = null
                }
                if (
                    initialFeedState == InitialFeedState.ContentReady ||
                    initialFeedState == InitialFeedState.Empty ||
                    initialFeedState == InitialFeedState.Failed
                ) {
                    initialFeedSlowJob?.cancel()
                    initialFeedSlowJob = null
                }
            },
        )
        relayHistoryCoordinator = coordinator
        coordinator.updateRelays(targetRelays)
        coordinator.start()
    }

    private fun scheduleInitialFeedSlowState() {
        initialFeedSlowJob?.cancel()
        if (currentFeedState().events.isNotEmpty()) return
        initialFeedSlowJob = launch {
            delay(INITIAL_FEED_SLOW_DELAY_MS)
            initialFeedSlowJob = null
            updateFeedState { current ->
                if (
                    current.events.isEmpty() &&
                    current.initialFeedState == InitialFeedState.Loading
                ) {
                    current.copy(
                        isInitialLoad = true,
                        initialFeedState = InitialFeedState.Slow,
                    )
                } else {
                    current
                }
            }
        }
    }

    private suspend fun requestHistoryPage(
        until: Long?,
        retryRelayUrls: Set<String>? = null,
    ) {
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
        activeHistoryPageSize = when {
            retryRelayUrls != null -> activeHistoryPageSize
            until == null -> FEED_PAGE_SIZE
            else -> nextHistoryPageSize
        }
        loadingMore = true
        lastHistoryBatchUniqueCount = 0
        val target = retryRelayUrls
            ?.takeIf { it.isNotEmpty() }
            ?.let(RelayTarget::Explicit)
            ?: relayTarget
        if (retryRelayUrls == null) {
            historyPageCreatedAtByEventId.clear()
            historyPageEventIdsByRelay.clear()
            pendingHistoryRelayUrls.clear()
        }
        // 初回のみライブ購読も開始（since=現在時刻でライブイベントのみ）
        if (until == null && retryRelayUrls == null) {
            initialHistoryRequested = true
            subscribeLiveFeed(since = Clock.System.now().epochSeconds)
        }
        updateFeedState { it.copy(canLoadMore = false, isLoadingMore = true) }
        val session = subscriptions.open(
            SubscriptionSpec(
                id = historySubId,
                filters = feedFilters(until = until, limit = activeHistoryPageSize),
                target = target,
                behavior = SubscriptionBehavior.Fetch(HISTORY_FETCH_TIMEOUT_MS),
                deduplicateEvents = false,
            ),
        )
        currentHistorySession = session
        startHistoryCollector(session)
    }

    /** 既存の表示を維持したまま、復帰後に必要な範囲だけ同期する。 */
    private suspend fun requestBackgroundSync(since: Long?, until: Long) {
        val ids = subscriptionIds ?: return
        if (authorPubkeys?.isEmpty() == true) return
        currentHistorySession?.close()
        val historySubId = nextHistorySubscriptionId(ids)
        isGapFill = true
        activeHistoryPageSize = FEED_PAGE_SIZE
        loadingMore = true
        lastHistoryBatchUniqueCount = 0
        historyPageCreatedAtByEventId.clear()
        historyPageEventIdsByRelay.clear()
        pendingHistoryRelayUrls.clear()
        val session = subscriptions.open(
            SubscriptionSpec(
                id = historySubId,
                filters = feedFilters(since = since, until = until, limit = FEED_PAGE_SIZE),
                target = relayTarget,
                behavior = SubscriptionBehavior.Fetch(HISTORY_FETCH_TIMEOUT_MS),
                deduplicateEvents = false,
            ),
        )
        currentHistorySession = session
        startHistoryCollector(session)
    }

    private fun onHistoryPageCompleted() {
        if (!loadingMore) return
        flushPendingTimelineEvents()
        loadingMore = false
        // リプライ等がフィルタされても受信件数が上限に達していれば次ページがある
        val relayHasMore = historyPageEventIdsByRelay.values.any { it.size >= activeHistoryPageSize }
        val pageWindow = historyPageWindow(
            createdAts = historyPageCreatedAtByEventId.values.toList(),
            pageSize = activeHistoryPageSize,
            hasMore = relayHasMore,
        )
        val hasMore = pageWindow.hasMore
        val loadedVisibleEvents = lastHistoryBatchUniqueCount > 0
        if (!isGapFill) {
            shouldRetryHistoryPage = false
            nextHistoryUntil = pageWindow.nextUntil
            nextHistoryPageSize = if (
                pageWindow.nextUntil != null && pageWindow.nextUntil == activeHistoryUntil
            ) {
                // created_at は秒精度なので、同じ境界秒にさらにイベントがある間は
                // until を進めず取得上限を広げ、31件目以降を取りこぼさない。
                (activeHistoryPageSize * 2).coerceAtMost(MAX_HISTORY_PAGE_SIZE)
            } else {
                FEED_PAGE_SIZE
            }
            revealHistoryThrough(pageWindow.revealOldestAt)
        }
        val cur = currentFeedState()
        // ギャップ補完は期間が限定されるため件数で過去ページの有無を判断できない
        setFeedState(cur.copy(
            canLoadMore = if (isGapFill) cur.canLoadMore else hasMore,
            isInitialLoad = false,
            isLoadingMore = false,
            isRefreshing = false,
            historyRequestGeneration = historyRequestGeneration,
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
        flushPendingTimelineEvents()
        loadingMore = false
        val pageWindow = historyPageWindow(
            createdAts = historyPageCreatedAtByEventId.values.toList(),
            pageSize = activeHistoryPageSize,
            hasMore = false,
        )
        if (!isGapFill) {
            // 一部リレーが未完了のページでは、安全なページ境界を確定できない。
            shouldRetryHistoryPage = true
            nextHistoryUntil = activeHistoryUntil
            revealHistoryThrough(pageWindow.revealOldestAt)
        }
        val current = currentFeedState()
        setFeedState(current.copy(
            isInitialLoad = false,
            canLoadMore = if (isGapFill) {
                current.canLoadMore
            } else {
                pendingHistoryRelayUrls.isNotEmpty()
            },
            isLoadingMore = false,
            isRefreshing = false,
            historyRequestGeneration = historyRequestGeneration,
        ))
        refreshIndicatorTimeoutJob?.cancel()
        refreshIndicatorTimeoutJob = null
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
        historyIndicatorSettleJob?.cancel()
        historyIndicatorSettleJob = null
        historyCollectorJobs += launch {
            session.signals.collect { signal ->
                if (currentHistorySession !== session) return@collect
                when (signal) {
                    is SubscriptionSignal.Event -> {
                        val event = signal.event
                        historyPageCreatedAtByEventId[event.id] = event.createdAt
                        historyPageEventIdsByRelay
                            .getOrPut(signal.relayUrl) { mutableSetOf() }
                            .add(event.id)
                        lastHistoryBatchUniqueCount += appendFeedEvent(event)
                        if (currentFeedState().isRefreshing && lastHistoryBatchUniqueCount > 0) {
                            clearRefreshIndicator()
                        }
                        if (currentFeedState().isInitialLoad && currentFeedState().events.isNotEmpty()) {
                            updateFeedState { it.copy(isInitialLoad = false) }
                        }
                    }
                    is SubscriptionSignal.Eose -> scheduleHistoryIndicatorSettle(session)
                    is SubscriptionSignal.FetchCompleted -> {
                        historyIndicatorSettleJob?.cancel()
                        historyIndicatorSettleJob = null
                        currentHistorySession = null
                        // 対象リレーは open 時に確定するため、事前スナップショットではなく
                        // 実際の完了結果から未完了リレーを再構築する。
                        pendingHistoryRelayUrls.clear()
                        pendingHistoryRelayUrls += signal.outcomes
                            .filterValues { it !is RelayOutcome.Eose }
                            .keys
                        val incomplete = pendingHistoryRelayUrls.isNotEmpty()
                        if (incomplete) onHistoryFetchIncomplete() else onHistoryPageCompleted()
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * 1台が応答済みなら、短い猶予の後にインジケーターだけを止める。
     * 取得セッションは継続し、遅いリレーのイベントとページ境界を失わない。
     */
    private fun scheduleHistoryIndicatorSettle(session: SubscriptionSession) {
        if (historyIndicatorSettleJob != null) return
        historyIndicatorSettleJob = launch {
            delay(HISTORY_RELAY_SETTLE_DELAY_MS)
            if (currentHistorySession !== session || !loadingMore) return@launch
            historyIndicatorSettleJob = null
            flushPendingTimelineEvents()
            if (!isGapFill) {
                revealHistoryThrough(
                    historyPageWindow(
                        historyPageCreatedAtByEventId.values.toList(),
                        FEED_PAGE_SIZE,
                    ).revealOldestAt,
                )
            }
            updateFeedState { it.copy(isLoadingMore = false) }
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
        val filters = feedFilters(since = since)
        val existing = liveSession
        if (existing != null) {
            existing.update(filters, relayTarget)
            return
        }

        val session = subscriptions.open(
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

    private fun feedFilters(
        since: Long? = null,
        until: Long? = null,
        limit: Int? = null,
    ): List<NostrFilter> = buildList {
        val kinds = feedEventKinds.filterTo(linkedSetOf()) { it != COMMENT_EVENT_KIND }
        if (includeRepostsInFeed && hashtag == null && 1 in feedEventKinds) kinds += 6
        if (kinds.isNotEmpty()) {
            add(
                NostrFilter(
                    kinds = kinds.toList(),
                    authors = authorPubkeys,
                    tTags = hashtag?.let { listOf(it) },
                    since = since,
                    until = until,
                    limit = limit,
                ),
            )
        }
        if (COMMENT_EVENT_KIND in feedEventKinds) {
            add(
                NostrFilter(
                    kinds = listOf(COMMENT_EVENT_KIND),
                    authors = authorPubkeys,
                    rootKindTags = listOf("1"),
                    tTags = hashtag?.let { listOf(it) },
                    since = since,
                    until = until,
                    limit = limit,
                ),
            )
        }
    }

    /** ポスト/リポストをフィード用に処理し、追加できた件数（0 or 1）を返す */
    private fun appendFeedEvent(event: NostrEvent): Int = when (event.kind) {
        1, COMMENT_EVENT_KIND -> {
            if (event.kind == COMMENT_EVENT_KIND && !event.isSupportedTimelineComment()) return 0
            val parentId = event.replyTargetId()
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
        if (event.kind !in DISPLAY_EVENT_KINDS || event.kind !in feedEventKinds) return 0
        if (event.kind == COMMENT_EVENT_KIND && !event.isSupportedTimelineComment()) return 0
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
            forgetEngagementHistory(removedId)
        }
        while (canonicalEvents.size > MAX_SEEN_IDS) canonicalEvents.remove(canonicalEvents.keys.first())
        if (oldestCreatedAt == null || timelineCreatedAt < (oldestCreatedAt ?: Long.MAX_VALUE)) {
            oldestCreatedAt = timelineCreatedAt
        }
        if (isFiltered(event)) return 0
        val cur = currentFeedState()
        if (cur.events.any { it.id == event.id } || pendingTimelineEvents.containsKey(event.id)) return 0
        pendingTimelineEvents[event.id] = event
        scheduleTimelineBatch()
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
        timelineBatchJob?.cancel()
        timelineBatchJob = null
        pendingTimelineEvents.clear()
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
                subscriptions.subscribe(
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
        if (cur.events.none { it.id == eventId } && eventId !in pendingTimelineEvents) return
        if (eventId in pendingTimelineEvents) return
        updateEvents(sortTimelineEvents(cur.events))
    }

    private fun sortTimelineEvents(events: List<NostrEvent>): List<NostrEvent> =
        FeedEventReducer.sort(events, eventSortTimes)

    private fun scheduleTimelineBatch() {
        if (closed) return
        if (timelineBatchJob?.isActive == true) return
        timelineBatchJob = launch {
            delay(TIMELINE_BATCH_DELAY_MS)
            timelineBatchJob = null
            flushPendingTimelineEvents()
        }
    }

    private fun flushPendingTimelineEvents() {
        timelineBatchJob?.cancel()
        timelineBatchJob = null
        if (closed || pendingTimelineEvents.isEmpty()) return
        val additions = pendingTimelineEvents.values.toList()
        pendingTimelineEvents.clear()
        val merged = sortTimelineEvents(currentFeedState().events + additions)
        updateEvents(merged, immediate = true)
    }

    private fun updateEvents(events: List<NostrEvent>, immediate: Boolean = false) {
        val visibleEvents = events
            .let { timelineEvents ->
                historyRevealOldestAt?.let { oldestVisibleAt ->
                    timelineEvents.filter { event ->
                        (eventSortTimes[event.id] ?: event.createdAt) >= oldestVisibleAt
                    }
                } ?: timelineEvents
            }
            .take(MAX_TIMELINE_EVENTS)
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
            current.reactionEvents
                .filterKeys { it in retainedEventIds }
                .values
                .flatten()
                .forEach { add(it.pubkey) }
            current.repostPubkeys
                .filterKeys { it in retainedEventIds }
                .values
                .flatten()
                .forEach(::add)
            current.repostedByPubkeys.forEach { (eventId, pubkey) ->
                if (eventId in visibleEventIds) add(pubkey)
            }
            ownPubkey?.let(::add)
        }
        val profiles = current.profiles.filterKeys { it in retainedPubkeys } +
            ProfileRepository.getCached(retainedPubkeys)

        setFeedState(current.copy(
            events = visibleEvents,
            isInitialLoad = if (visibleEvents.isNotEmpty()) false else current.isInitialLoad,
            initialFeedState = if (visibleEvents.isNotEmpty()) {
                initialFeedSlowJob?.cancel()
                initialFeedSlowJob = null
                InitialFeedState.ContentReady
            } else {
                current.initialFeedState
            },
            profiles = profiles,
            reactionCounts = current.reactionCounts.filterKeys { it in retainedEventIds },
            likeReactionCounts = current.likeReactionCounts.filterKeys { it in retainedEventIds },
            customReactions = current.customReactions.filterKeys { it in retainedEventIds },
            unicodeReactions = current.unicodeReactions.filterKeys { it in retainedEventIds },
            reactionEvents = current.reactionEvents.filterKeys { it in retainedEventIds },
            replyCounts = current.replyCounts.filterKeys { it in retainedEventIds },
            replies = replies,
            repostCounts = current.repostCounts.filterKeys { it in retainedEventIds },
            repostPubkeys = current.repostPubkeys.filterKeys { it in retainedEventIds },
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

    private fun rememberEngagementSeenId(
        seenIds: LinkedHashSet<String>,
        globalKey: String,
        batchKey: String,
        targetId: String,
    ): Boolean {
        val batch = engagementHistoryDedup
        if (batch != null && targetId in batch.eventIds && !batch.seenKeys.add(batchKey)) {
            return false
        }
        return rememberSeenId(seenIds, globalKey)
    }

    private fun revealHistoryThrough(createdAt: Long?) {
        if (createdAt == null) return
        historyRevealOldestAt = historyRevealOldestAt?.let { minOf(it, createdAt) } ?: createdAt
        rebuildFilteredEvents()
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

    private suspend fun resubscribeEngagement(retryPartialHistory: Boolean = false) {
        engagementSubscriptionMutex.withLock {
            resubscribeEngagementLocked(retryPartialHistory)
        }
    }

    private suspend fun resubscribeEngagementLocked(retryPartialHistory: Boolean) {
        val subIds = subscriptionIds ?: return
        if (watchedEventIds.isEmpty()) return
        val targetRelays = subscriptions.targetRelayUrls(relayTarget)
        val previousRelays = lastEngagementTargetRelays
        lastEngagementTargetRelays = targetRelays
        if (previousRelays != null) {
            val removedRelays = previousRelays - targetRelays
            if (removedRelays.isNotEmpty()) {
                engagementCompletedPartitions.values.forEach { completedByRelay ->
                    removedRelays.forEach(completedByRelay::remove)
                }
            }
        }
        if (retryPartialHistory) {
            engagementRetryJobs.values.forEach(Job::cancel)
            engagementRetryJobs.clear()
            engagementFailureCounts.clear()
            engagementHistoryStates.keys.toList().forEach { eventId ->
                if (engagementHistoryStates[eventId] == EngagementHistoryState.Partial) {
                    engagementHistoryStates[eventId] = EngagementHistoryState.Pending
                }
            }
        }
        val ids = watchedEventIds.toList()
        val cutoff = Clock.System.now().epochSeconds
        val liveSince = engagementResumeSince
            ?: (cutoff - ENGAGEMENT_LIVE_OVERLAP_SECONDS).coerceAtLeast(0L)
        engagementResumeSince = null
        val filters = engagementFilters(ids, since = liveSince)
        val existing = engagementSession
        if (existing != null) {
            existing.update(filters, relayTarget)
        } else {
            val session = subscriptions.open(
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
                        engagementEventMutex.withLock { handleEngagementEvent(signal.event) }
                    }
                }
            }
        }

        if (engagementHistorySession == null) {
            if (targetRelays.isEmpty()) {
                // 起動直後は RelayStore の読み込みと Repository への反映に時間差がある。
                // 取得先が未確定な状態を「全リレー取得済み」とみなさず、確定通知を待つ。
                engagementHistoryStates.keys.toList().forEach { eventId ->
                    if (engagementHistoryStates[eventId] != EngagementHistoryState.InFlight) {
                        engagementHistoryStates[eventId] = EngagementHistoryState.Pending
                    }
                }
                return
            }
            // Complete を事前に除外すると、targetRelaysが後から広がったときに
            // 「古いリレー集合では完了済みだが新しいリレーではまだ」というケースを
            // updateEngagementHistoryState() が再評価できなくなる。全件を渡す。
            val backfillIds = engagementHistoryStates.keys.toList()
            backfillIds.forEach { eventId -> updateEngagementHistoryState(eventId, targetRelays) }
            nextEngagementHistoryWork(backfillIds, targetRelays)?.let { work ->
                startEngagementHistoryFetch(
                    subIds = subIds,
                    work = work,
                    until = cutoff,
                )
            }
        }
    }

    private suspend fun startEngagementHistoryFetch(
        subIds: SubscriptionIds,
        work: EngagementHistoryWork,
        until: Long,
    ) {
        val eventIds = work.eventIds
        eventIds.forEach { engagementHistoryStates[it] = EngagementHistoryState.InFlight }
        engagementEventMutex.withLock {
            engagementHistoryDedup = EngagementHistoryDedup(eventIds.toSet())
        }
        val session = try {
            subscriptions.open(
                SubscriptionSpec(
                    id = "${subIds.reaction}-history-${++nextEngagementHistoryRequestId}",
                    filters = work.partitions.map { it.filter(eventIds, until = until) },
                    target = RelayTarget.Explicit(setOf(work.relayUrl)),
                    behavior = SubscriptionBehavior.Fetch(ENGAGEMENT_FETCH_TIMEOUT_MS),
                ),
            )
        } catch (error: Throwable) {
            engagementEventMutex.withLock { engagementHistoryDedup = null }
            eventIds.forEach { eventId ->
                if (engagementHistoryStates[eventId] == EngagementHistoryState.InFlight) {
                    engagementHistoryStates[eventId] = EngagementHistoryState.Partial
                }
            }
            if (error is CancellationException) throw error
            scheduleEngagementHistoryRetry(work)
            return
        }
        if (!subscriptionsStarted || subscriptionIds !== subIds) {
            engagementEventMutex.withLock { engagementHistoryDedup = null }
            eventIds.forEach { eventId ->
                if (engagementHistoryStates[eventId] == EngagementHistoryState.InFlight) {
                    engagementHistoryStates[eventId] = EngagementHistoryState.Partial
                }
            }
            session.close()
            return
        }
        engagementHistorySession = session
        var closedDisposition: RetryDisposition? = null
        subscriptionJobs += launch {
            session.signals.collect { signal ->
                if (engagementHistorySession !== session) return@collect
                when (signal) {
                    is SubscriptionSignal.Event -> engagementEventMutex.withLock {
                        handleEngagementEvent(signal.event)
                    }
                    is SubscriptionSignal.Closed -> if (signal.relayUrl == work.relayUrl) {
                        closedDisposition = signal.retry
                    }
                    is SubscriptionSignal.FetchCompleted -> {
                        var shouldRetryImmediately = false
                        var shouldRetryWithBackoff = false
                        engagementEventMutex.withLock {
                            val succeeded = !signal.timedOut &&
                                signal.outcomes[work.relayUrl] is RelayOutcome.Eose
                            if (succeeded) {
                                eventIds.forEach { eventId ->
                                    if (eventId !in engagementHistoryStates) return@forEach
                                    engagementCompletedPartitions
                                        .getOrPut(eventId) { mutableMapOf() }
                                        .getOrPut(work.relayUrl) { mutableSetOf() }
                                        .addAll(work.partitions)
                                }
                                engagementFailureCounts.remove(work.relayUrl)
                                engagementRetryJobs.remove(work.relayUrl)?.cancel()
                            } else {
                                val retry = closedDisposition ?: RetryDisposition.RetryWithBackoff
                                if (
                                    retry == RetryDisposition.RetryOnFilterChange &&
                                    work.partitions.size > 1
                                ) {
                                    isolatedEngagementRelays += work.relayUrl
                                    shouldRetryImmediately = true
                                } else if (retry == RetryDisposition.RetryWithBackoff) {
                                    shouldRetryWithBackoff = true
                                }
                            }
                            eventIds.forEach { eventId ->
                                if (eventId !in engagementHistoryStates) return@forEach
                                engagementHistoryStates[eventId] =
                                    if (succeeded || shouldRetryImmediately) {
                                        EngagementHistoryState.Pending
                                    } else {
                                        EngagementHistoryState.Partial
                                    }
                            }
                            engagementHistoryDedup = null
                        }
                        engagementHistorySession = null
                        session.close()
                        if (subscriptionsStarted) {
                            if (shouldRetryWithBackoff) {
                                scheduleEngagementHistoryRetry(work)
                            } else {
                                resubscribeEngagement()
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun nextEngagementHistoryWork(
        eventIds: List<String>,
        targetRelays: Set<String>,
    ): EngagementHistoryWork? {
        targetRelays.sorted().forEach { relayUrl ->
            val firstEventId = eventIds.firstOrNull { eventId ->
                engagementHistoryStates[eventId] == EngagementHistoryState.Pending &&
                    missingEngagementPartitions(eventId, relayUrl).isNotEmpty()
            } ?: return@forEach
            val missing = missingEngagementPartitions(firstEventId, relayUrl)
            val partitions = if (relayUrl in isolatedEngagementRelays) {
                setOf(missing.first())
            } else {
                missing
            }
            val batch = eventIds.asSequence()
                .filter { engagementHistoryStates[it] == EngagementHistoryState.Pending }
                .filter { eventId -> missingEngagementPartitions(eventId, relayUrl).containsAll(partitions) }
                .take(ENGAGEMENT_HISTORY_BATCH_SIZE)
                .toList()
            if (batch.isNotEmpty()) {
                return EngagementHistoryWork(relayUrl, batch, partitions)
            }
        }
        return null
    }

    private fun missingEngagementPartitions(eventId: String, relayUrl: String): Set<EngagementPartition> =
        EngagementPartition.entries.toSet() -
            engagementCompletedPartitions[eventId]?.get(relayUrl).orEmpty()

    private fun updateEngagementHistoryState(eventId: String, targetRelays: Set<String>) {
        val complete = targetRelays.all { relayUrl ->
            missingEngagementPartitions(eventId, relayUrl).isEmpty()
        }
        engagementHistoryStates[eventId] = when {
            complete -> EngagementHistoryState.Complete
            engagementHistoryStates[eventId] == EngagementHistoryState.Complete -> EngagementHistoryState.Pending
            else -> engagementHistoryStates[eventId] ?: EngagementHistoryState.Pending
        }
    }

    private fun scheduleEngagementHistoryRetry(work: EngagementHistoryWork) {
        if (!subscriptionsStarted || engagementRetryJobs[work.relayUrl]?.isActive == true) return
        val attempt = (engagementFailureCounts[work.relayUrl] ?: 0) + 1
        engagementFailureCounts[work.relayUrl] = attempt
        if (attempt > MAX_ENGAGEMENT_HISTORY_RETRIES) return
        val delayMillis = (ENGAGEMENT_RETRY_BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4)))
            .coerceAtMost(ENGAGEMENT_RETRY_MAX_DELAY_MS)
        engagementRetryJobs[work.relayUrl] = launch {
            delay(delayMillis)
            engagementRetryJobs.remove(work.relayUrl)
            work.eventIds.forEach { eventId ->
                if (engagementHistoryStates[eventId] == EngagementHistoryState.Partial) {
                    engagementHistoryStates[eventId] = EngagementHistoryState.Pending
                }
            }
            resubscribeEngagement()
        }
    }

    private fun scheduleEngagementFetch(eventId: String) {
        if (!watchedEventIds.add(eventId)) return
        engagementHistoryStates[eventId] = EngagementHistoryState.Pending
        while (watchedEventIds.size > MAX_TRACKED_ENGAGEMENT_EVENTS) {
            // 追跡数の上限に達しても、取得進捗(engagementHistoryStates /
            // engagementCompletedPartitions)はここでは消さない。投稿自体がフィードから
            // 消えるまでは forgetEngagementHistory() 経由でのみ破棄する。
            watchedEventIds.remove(watchedEventIds.first())
        }
        val startImmediately = engagementSession == null
        engagementBatchJob?.cancel()
        engagementBatchJob = launch {
            if (!startImmediately) delay(ENGAGEMENT_BATCH_DELAY_MS)
            resubscribeEngagement()
        }
    }

    /** 投稿がフィードから完全に消えるとき、リアクション取得の追跡状態も合わせて破棄する。 */
    private fun forgetEngagementHistory(eventId: String) {
        watchedEventIds.remove(eventId)
        engagementHistoryStates.remove(eventId)
        engagementCompletedPartitions.remove(eventId)
    }

    private fun engagementFilters(
        ids: List<String>,
        since: Long? = null,
        until: Long? = null,
    ): List<NostrFilter> = EngagementPartition.entries.map { it.filter(ids, since, until) }

    private fun handleEngagementEvent(event: NostrEvent) {
        when (event.kind) {
            7 -> handleReactionEvent(event)
            6 -> handleEngagementRepostEvent(event)
            1, COMMENT_EVENT_KIND -> {
                handleReplyEvent(event)
                if (event.kind == 1) handleQuoteRepostEvent(event)
            }
        }
    }

    private fun handleReactionEvent(event: NostrEvent) {
        val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
            ?.takeIf { it in engagementHistoryStates }
            ?: return
        if (!rememberEngagementSeenId(
                seenIds = seenReactionIds,
                globalKey = event.id,
                batchKey = "reaction:${event.id}:$targetId",
                targetId = targetId,
            )
        ) return
        rememberReceivedEvent(receivedReactionEvents, event)
        scheduleProfileFetch(event.pubkey)
        val cur = currentFeedState()
        val isOwn = ownPubkey != null && event.pubkey == ownPubkey
        setFeedState(
            EngagementAccumulator.reaction(cur, targetId, event, isOwn),
            immediate = false,
        )
    }

    private fun handleReplyEvent(event: NostrEvent) {
        if (event.kind == COMMENT_EVENT_KIND && !event.isSupportedTimelineComment()) return
        val targetId = event.replyTargetId()
            ?.takeIf { it in engagementHistoryStates }
            ?: return
        if (!rememberEngagementSeenId(
                seenIds = seenReplyIds,
                globalKey = event.id,
                batchKey = "reply:${event.id}:$targetId",
                targetId = targetId,
            )
        ) return
        scheduleProfileFetch(event.pubkey)
        scheduleMentionedProfileFetch(event.content)
        setFeedState(
            EngagementAccumulator.reply(currentFeedState(), targetId, event),
            immediate = false,
        )
    }

    private fun handleEngagementRepostEvent(event: NostrEvent) {
        val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
            ?.takeIf { it in engagementHistoryStates }
            ?: return
        if (!rememberEngagementSeenId(
                seenIds = seenRepostIds,
                globalKey = event.id,
                batchKey = "repost:${event.id}:$targetId",
                targetId = targetId,
            )
        ) return
        rememberReceivedEvent(receivedRepostEvents, event)
        scheduleProfileFetch(event.pubkey)
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
            .filter { it in engagementHistoryStates }
            .filter { targetId ->
                val key = "${event.id}:$targetId"
                rememberEngagementSeenId(
                    seenIds = seenQuoteRepostIds,
                    globalKey = key,
                    batchKey = "quote:$key",
                    targetId = targetId,
                )
            }
        if (targetIds.isEmpty()) return
        setFeedState(
            EngagementAccumulator.quoteReposts(currentFeedState(), targetIds, event.pubkey),
            immediate = false,
        )
        scheduleProfileFetch(event.pubkey)
    }

    private fun scheduleQuoteFetch(eventIds: List<String>) {
        val missingIds = eventIds.filter { id ->
            id !in currentFeedState().quotedEvents && pendingQuoteIds.add(id)
        }
        if (missingIds.isEmpty()) return
        launch {
            val ids = subscriptionIds ?: return@launch
            subscriptions.subscribe(
                ids.quote,
                NostrFilter(ids = pendingQuoteIds.toList(), kinds = DISPLAY_EVENT_KINDS.toList()),
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
        private val DISPLAY_EVENT_KINDS = linkedSetOf(1, COMMENT_EVENT_KIND)
        private const val FEED_PAGE_SIZE = 30
        private const val MAX_HISTORY_PAGE_SIZE = 3_840
        private const val MAX_TIMELINE_EVENTS = 800
        private const val MAX_TRACKED_ENGAGEMENT_EVENTS = 100
        private const val MAX_SEEN_IDS = 2000
        private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
        private const val TIMELINE_BATCH_DELAY_MS = 150L
        private const val FEED_STATE_EMIT_DELAY_MS = 150L
        private const val REFRESH_INDICATOR_TIMEOUT_MS = 2_500L
        private const val INITIAL_FEED_SLOW_DELAY_MS = 2_500L
        private const val HISTORY_FETCH_TIMEOUT_MS = 10_000L
        private const val HISTORY_RELAY_SETTLE_DELAY_MS = 2_000L
        private const val ENGAGEMENT_FETCH_TIMEOUT_MS = 10_000L
        private const val ENGAGEMENT_BATCH_DELAY_MS = 500L
        private const val ENGAGEMENT_HISTORY_BATCH_SIZE = 20
        private const val MAX_ENGAGEMENT_HISTORY_RETRIES = 4
        private const val ENGAGEMENT_RETRY_BASE_DELAY_MS = 1_000L
        private const val ENGAGEMENT_RETRY_MAX_DELAY_MS = 30_000L
        private const val ENGAGEMENT_LIVE_OVERLAP_SECONDS = 120L
        private const val MAX_AUTO_SKIP_EMPTY_HISTORY_PAGES = 5
        private var nextInstanceKeyValue = 0

        private fun nextInstanceKey(): Int = ++nextInstanceKeyValue
    }
}

internal enum class ResumeSyncStrategy {
    None,
    GapFill,
    LatestPage,
}

/**
 * 復帰時の見せ方は変えず、裏側で取得する範囲だけを経過時間に応じて制限する。
 * 長期間の全件gap fillは復帰直後の負荷が大きいため、直近ページの取得へ切り替える。
 */
internal fun resumeSyncStrategy(gapSince: Long?, nowSec: Long): ResumeSyncStrategy {
    if (gapSince == null) return ResumeSyncStrategy.LatestPage
    val gapSeconds = (nowSec - gapSince).coerceAtLeast(0)
    return when {
        gapSeconds <= RESUME_SYNC_GRACE_PERIOD_SECONDS -> ResumeSyncStrategy.None
        gapSeconds <= MAX_GAP_FILL_DURATION_SECONDS -> ResumeSyncStrategy.GapFill
        else -> ResumeSyncStrategy.LatestPage
    }
}

private const val RESUME_SYNC_GRACE_PERIOD_SECONDS = 5L
private const val MAX_GAP_FILL_DURATION_SECONDS = 24L * 60L * 60L

internal fun FeedViewModel.UiState.noteEngagement(eventId: String): NoteEngagementState = NoteEngagementState(
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

internal fun FeedViewModel.UiState.withCardSnapshot(
    snapshot: NoteCardSnapshot,
): FeedViewModel.UiState {
    val withReplyCount = snapshot.replyCount?.let { count ->
        copy(replyCounts = replyCounts + (snapshot.eventId to maxOf(replyCounts[snapshot.eventId] ?: 0, count)))
    } ?: this
    return snapshot.engagement?.let { withReplyCount.withEngagement(snapshot.eventId, it) } ?: withReplyCount
}

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

private data class EngagementHistoryDedup(
    val eventIds: Set<String>,
    val seenKeys: MutableSet<String> = mutableSetOf(),
)

private data class EngagementHistoryWork(
    val relayUrl: String,
    val eventIds: List<String>,
    val partitions: Set<EngagementPartition>,
)

private enum class EngagementPartition {
    Reaction,
    KindOneReply,
    Nip22Reply,
    Nip22RootReply,
    Repost,
    QuoteRepost;

    fun filter(ids: List<String>, since: Long? = null, until: Long? = null): NostrFilter = when (this) {
        Reaction -> NostrFilter(kinds = listOf(7), eTags = ids, since = since, until = until)
        KindOneReply -> NostrFilter(kinds = listOf(1), eTags = ids, since = since, until = until)
        Nip22Reply -> NostrFilter(
            kinds = listOf(COMMENT_EVENT_KIND),
            rootKindTags = listOf("1"),
            eTags = ids,
            since = since,
            until = until,
        )
        Nip22RootReply -> NostrFilter(
            kinds = listOf(COMMENT_EVENT_KIND),
            rootKindTags = listOf("1"),
            rootEventTags = ids,
            since = since,
            until = until,
        )
        Repost -> NostrFilter(kinds = listOf(6), eTags = ids, since = since, until = until)
        QuoteRepost -> NostrFilter(kinds = listOf(1), qTags = ids, since = since, until = until)
    }
}

private enum class EngagementHistoryState {
    Pending,
    InFlight,
    Complete,
    Partial,
}

private data class SubscriptionIds(
    val feed: String,
    val history: String,
    val reaction: String,
    val repostTarget: String,
    val quote: String,
)
