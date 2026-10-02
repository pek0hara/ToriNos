package com.nostr.torinos.ui.feed

import com.nostr.torinos.ui.feed.FeedViewModel.UiState
import com.nostr.torinos.util.appLog
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    authorPubkeys: List<String>? = authorPubkey?.let { listOf(it) },
    private val relayUrl: String? = null,
    private val autoStart: Boolean = true,
    private val includeRepostsInFeed: Boolean = false,
    private val includeRepliesInFeed: Boolean = false,
    private val hashtag: String? = null,
    private val filterMutedUsers: Boolean = true,
    private val scope: CoroutineScope,
    private val subscriptions: FeedSubscriptionGateway = RepositoryFeedSubscriptionGateway,
    private val feedEventKinds: Set<Int> = setOf(1),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val safeCoroutineLauncher = SafeCoroutineLauncher(scope, "FeedController")
    /** フォロー一覧の更新で差し替わる。購読フィルタは都度ここから組み立てる。 */
    private var authorPubkeys: List<String>? = authorPubkeys
    private val feedItemMapper = FeedItemMapper()
    private val mentionedPubkeysCache = MentionedPubkeysCache()
    /** UI から「先頭にいるか」の境界値だけを受け取る。高頻度なスクロール位置そのものは渡さない。 */
    private var isAtTop = true

    fun setAtTop(value: Boolean) {
        if (isAtTop == value) return
        isAtTop = value
        if (value && currentFeedState().newPostCount != 0) {
            setFeedState(currentFeedState().copy(newPostCount = 0), immediate = false)
        }
    }
    private fun launch(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = safeCoroutineLauncher.launch(start = start, block = block)

    private val _state = StateStore(UiState())
    val state: StateFlow<UiState> = _state.state
    private var pendingFeedState = UiState()
    private var feedStateRevision = 0L
    private var feedStateEmitJob: Job? = null
    private val pendingTimelineEvents = linkedMapOf<String, NostrEvent>()
    private var timelineBatchJob: Job? = null
    private var closed = false

    private val instanceKey = nextInstanceKey()
    private val shortKey = authorPubkey?.take(16) ?: authorPubkeys?.hashCode()?.toString() ?: "global"
    private val subscriptionJobs = mutableListOf<Job>()
    private val lifecycleJobs = mutableListOf<Job>()
    private var subscriptionIds: SubscriptionIds? = null
    private var liveSession: SubscriptionSession? = null
    private var engagementSession: SubscriptionSession? = null
    // ライブ購読へ最後に送った監視ID。since は呼び出しごとに進むため、IDが同じなら REQ を再送しない。
    private var engagementLiveEventIds: List<String>? = null
    private val engagementHistorySessions = mutableMapOf<String, SubscriptionSession>()
    private var relayHistoryCoordinator: RelayFeedHistoryCoordinator? = null
    /**
     * 差分取得。途中でタブを離れても再開して最後まで取りきる。
     * 復帰・手動更新は全リレー ([ALL_RELAYS_SYNC_KEY])、ライブ配信の途切れはそのリレーだけを対象にし、
     * 対象ごとに 1 本だけ持つ。新しい差分取得は未完了分の範囲も引き継ぐ。
     */
    private val feedSyncs = mutableMapOf<String, FeedSync>()
    private var feedSyncSequence = 0
    /** リレー単位の取り直しで追加した件数の合計。取り直しがすべて終わったら数え直す。 */
    private var liveGapRecoveryAddedCount = 0
    /** 手動更新を過去ページの取得で処理しているとき、その完了で更新表示を消す。 */
    private var historyOwnsRefresh = false
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
    private val engagementHistoryDedups = mutableMapOf<String, EngagementHistoryDedup>()
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

    private var historyRevealOldestAt: Long? = null
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
            ProfileRepository.observeChanges().collect { changedPubkeys ->
                val currentProfiles = currentFeedState().profiles
                val relevantPubkeys = currentProfiles.keys + requestedProfilePubkeys
                if (relevantPubkeys.isEmpty()) return@collect
                val changedRelevant = if (changedPubkeys.isEmpty()) {
                    relevantPubkeys
                } else {
                    changedPubkeys.filterTo(linkedSetOf()) { it in relevantPubkeys }
                }
                val updatedProfiles = ProfileRepository.getCached(changedRelevant)
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
                feedSyncs[ALL_RELAYS_SYNC_KEY]?.coordinator?.updateRelays(targetRelays)
                feedSyncs.keys.filter { it != ALL_RELAYS_SYNC_KEY && it !in targetRelays }.forEach { url ->
                    feedSyncs.remove(url)?.coordinator?.close()
                }
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
        feedStateRevision++
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
        updateEvents(immediate = true) { current -> current.events.filter { it.id != eventId } }
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
        val coordinator = relayHistoryCoordinator ?: return
        if (currentFeedState().canLoadMore) coordinator.loadMore()
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
        resetFeedState()
        return true
    }

    /**
     * 対象著者を差し替え、フィードを最初から取り直す。購読中なら新しい著者で再開する。
     * フォロー一覧の更新ごとに ViewModel を作り直さずに済ませるための入口。
     */
    fun updateAuthors(authors: List<String>?): Boolean {
        if (closed || authors == authorPubkeys) return false
        val wasStarted = subscriptionsStarted
        authorPubkeys = authors
        resetFeedState()
        if (wasStarted) startSubscriptions()
        return true
    }

    private fun resetFeedState() {
        stopSubscriptions()
        timelineBatchJob?.cancel()
        timelineBatchJob = null
        feedStateEmitJob?.cancel()
        feedStateEmitJob = null
        refreshIndicatorTimeoutJob?.cancel()
        refreshIndicatorTimeoutJob = null
        initialFeedSlowJob?.cancel()
        initialFeedSlowJob = null
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
        engagementHistoryDedups.clear()
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
        closeFeedSyncs()
        historyOwnsRefresh = false

        historyRevealOldestAt = null
        initialHistoryRequested = false
        manualRefreshRequested = false
        pendingFeedState = UiState()
        _state.value = pendingFeedState
        isAtTop = true
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
            } else if (manualRefreshRequested && relayHistoryCoordinator == null) {
                manualRefreshRequested = false
                startRelayHistory(forRefresh = true)
                resubscribeEngagement(retryPartialHistory = true)
            } else {
                // タブ再表示・手動更新では、読み込み済みの過去ページを保ったまま
                // ライブ購読を再開し、離れていた間の投稿を取りきる。
                val refreshing = manualRefreshRequested
                manualRefreshRequested = false
                relayHistoryCoordinator?.resume()
                // 手動更新は、拒否や再試行待ちで止まっているリレーにもすぐ問い合わせ直す。
                if (refreshing) relayHistoryCoordinator?.retryStalledRelays()
                feedSyncs.values.toList().forEach { it.coordinator.resume() }
                val nowSec = Clock.System.now().epochSeconds
                subscribeLiveFeed(since = nowSec)
                // 時計のずれた未来の投稿を基準にすると、差分を取り損ねる。
                val gapSince = eventSortTimes.values.filter { it <= nowSec }.maxOrNull()
                when (resumeSyncStrategy(gapSince = gapSince, nowSec = nowSec)) {
                    ResumeSyncStrategy.None ->
                        if (refreshing) startFeedSync(since = gapSince, until = nowSec)
                    ResumeSyncStrategy.GapFill -> startFeedSync(since = gapSince, until = nowSec)
                    ResumeSyncStrategy.LatestPage -> startFeedSync(since = null, until = nowSec)
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
        initialFeedSlowJob?.cancel()
        initialFeedSlowJob = null
        // 過去ページのカーソルは復帰後の「もっと読む」に必要なので、通信だけ止める。
        relayHistoryCoordinator?.pause()
        feedSyncs.values.forEach { it.coordinator.pause() }
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
        if (clearRefreshing) clearRefreshIndicator()
        if (currentFeedState().isInitialLoad && currentFeedState().events.isEmpty()) {
            initialHistoryRequested = false
        }
        val sessionsToClose = listOfNotNull(
            liveSession,
            engagementSession,
        ) + engagementHistorySessions.values
        liveSession = null
        engagementSession = null
        engagementLiveEventIds = null
        engagementHistorySessions.clear()
        engagementHistoryDedups.clear()
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
        relayHistoryCoordinator?.close()
        relayHistoryCoordinator = null
        closeFeedSyncs()
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
    private suspend fun startRelayHistory(forRefresh: Boolean = false) {
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
        historyOwnsRefresh = forRefresh
        val historyFloor = Clock.System.now().epochSeconds
        initialHistoryRequested = true
        subscribeLiveFeed(since = historyFloor)
        val targetRelays = subscriptions.targetRelayUrls(relayTarget)
        // 初回は表示境界が決まるまで過去分を出さない。先に返ったリレーの深い投稿を
        // 一度表示してから引っ込める、ちらつきを避けるため。
        if (historyRevealOldestAt == null) historyRevealOldestAt = historyFloor
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
            onEvent = ::appendHistoryEvent,
            onReveal = { floor ->
                revealHistoryThrough(floor)
                flushPendingTimelineEvents()
            },
            onFlush = ::flushPendingTimelineEvents,
            onState = { history ->
                historyRequestGeneration = history.generation
                val current = currentFeedState()
                val initialFeedState = when {
                    current.events.isNotEmpty() -> InitialFeedState.ContentReady
                    // 表示境界の確定直後は一覧の再構築が非同期なので、空と誤判定しない。
                    hasRevealableEvents() -> current.initialFeedState
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
                        historyRequestGeneration = history.generation,
                    )
                }
                // 復帰時の差分取得が担う更新表示は、過去ページの状態で消さない。
                if (historyOwnsRefresh && !history.isLoading) {
                    historyOwnsRefresh = false
                    clearRefreshIndicator()
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

    /** 過去ページと差分取得で共通の、受信イベントの取り込み。 */
    private fun appendHistoryEvent(event: NostrEvent): Int {
        val added = appendFeedEvent(event)
        if (currentFeedState().isRefreshing && added > 0) clearRefreshIndicator()
        return added
    }

    /**
     * 既存の表示を維持したまま、[since] 以降の投稿をリレーごとに最後まで取得する。
     * [since] がない（離れていた時間が長すぎる）ときは、各リレーの直近 1 ページだけを取る。
     * [relayUrl] を指定するとそのリレーだけを対象にする。同じ対象の未完了の差分取得があれば、
     * その範囲も含めて 1 本に取り直す（重複は ID で除く）。
     */
    private suspend fun startFeedSync(since: Long?, until: Long, relayUrl: String? = null) {
        val key = relayUrl ?: ALL_RELAYS_SYNC_KEY
        if (authorPubkeys?.isEmpty() == true) {
            if (relayUrl == null) clearRefreshIndicator()
            return
        }
        val allRelays = subscriptions.targetRelayUrls(relayTarget)
        // 待っている間にタブを離れた・閉じた場合は始めない。ここから登録までは中断しない。
        if (closed || !subscriptionsStarted) return
        val targetRelays = relayUrl?.let { allRelays.intersect(setOf(it)) } ?: allRelays
        val previous = feedSyncs.remove(key)
        val effectiveSince = when {
            previous == null || since == null -> since
            else -> minOf(since, previous.since ?: since)
        }
        val effectiveUntil = previous?.let { maxOf(until, it.until) } ?: until
        previous?.coordinator?.close()
        val isLiveGapRecovery = relayUrl != null
        lateinit var sync: FeedSync
        val coordinator = RelayFeedHistoryCoordinator(
            scope = scope,
            subscriptions = subscriptions,
            idPrefix = "feed-$instanceKey-sync-${++feedSyncSequence}",
            baseFilters = feedFilters(since = effectiveSince),
            historyFloor = effectiveUntil,
            fetchTimeoutMillis = HISTORY_FETCH_TIMEOUT_MS,
            pageSize = FEED_PAGE_SIZE,
            maxPageSize = MAX_HISTORY_PAGE_SIZE,
            settleDelayMillis = HISTORY_RELAY_SETTLE_DELAY_MS,
            revealsHistory = false,
            autoContinue = effectiveSince != null,
            onEvent = { event ->
                // ライブ配信の取り直しは手動更新とは無関係なので、更新表示には触らない。
                val added = if (isLiveGapRecovery) appendFeedEvent(event) else appendHistoryEvent(event)
                sync.addedCount += added
                if (isLiveGapRecovery) liveGapRecoveryAddedCount += added
                added
            },
            onFlush = ::flushPendingTimelineEvents,
            onState = { state ->
                // 全リレーが一斉に切れても上限を超えないよう、リレー単位の取り直しは件数を合算する。
                val overflowed = if (isLiveGapRecovery) {
                    liveGapRecoveryAddedCount > MAX_FEED_SYNC_EVENTS
                } else {
                    sync.addedCount > MAX_FEED_SYNC_EVENTS
                }
                // コールバック中に自身を閉じないよう、完了処理は後で行う。
                when {
                    overflowed -> launch { onFeedSyncOverflow(key, sync) }
                    state.relayCount == 0 ||
                        (if (effectiveSince != null) !state.canLoadMore else state.isInitialFetchSettled) ->
                        launch { finishFeedSync(key, sync) }
                }
            },
        )
        sync = FeedSync(coordinator, effectiveSince, effectiveUntil)
        feedSyncs[key] = sync
        coordinator.updateRelays(targetRelays)
        coordinator.start()
    }

    private suspend fun recoverLiveGap(signal: SubscriptionSignal.Resumed) {
        val nowSec = Clock.System.now().epochSeconds
        // 復帰時と同じく 24 時間より前は取りに行かない。
        val since = maxOf(
            signal.interruptedAt - LIVE_RESUME_OVERLAP_SECONDS,
            nowSec - MAX_GAP_FILL_DURATION_SECONDS,
        ).coerceAtLeast(0L)
        val until = minOf(signal.replayOldestAt, nowSec)
        // 全リレーの差分取得が取りきる区間は任せ、その先だけを取り直す。
        val allSync = feedSyncs[ALL_RELAYS_SYNC_KEY]
        val from = if (allSync?.since != null && allSync.since <= since) maxOf(since, allSync.until) else since
        if (from > until) return
        startFeedSync(since = from, until = until, relayUrl = signal.relayUrl)
    }

    private fun finishFeedSync(key: String, sync: FeedSync) {
        if (feedSyncs[key] !== sync) return
        feedSyncs.remove(key)
        sync.coordinator.close()
        flushPendingTimelineEvents()
        if (key == ALL_RELAYS_SYNC_KEY) clearRefreshIndicator()
        if (feedSyncs.keys.none { it != ALL_RELAYS_SYNC_KEY }) liveGapRecoveryAddedCount = 0
    }

    private fun closeFeedSyncs() {
        feedSyncs.values.forEach { it.coordinator.close() }
        feedSyncs.clear()
        liveGapRecoveryAddedCount = 0
    }

    /**
     * 差分の新着が多すぎるとき、保持上限を超えて読み込み済みの過去ページが押し出されるのを防ぐ。
     * 復帰・手動更新ではユーザーが戻った直後なので最新から読み直し、読んでいる最中に起きる
     * リレー単位の取り直しでは一覧を崩さず、その取り直しだけを打ち切る。
     */
    private fun onFeedSyncOverflow(key: String, sync: FeedSync) {
        if (closed || feedSyncs[key] !== sync) return
        if (key != ALL_RELAYS_SYNC_KEY) {
            appLog("[FeedController] live gap recovery stopped after $liveGapRecoveryAddedCount events")
            feedSyncs.filterKeys { it != ALL_RELAYS_SYNC_KEY }.forEach { (relayKey, relaySync) ->
                finishFeedSync(relayKey, relaySync)
            }
            return
        }
        val wasStarted = subscriptionsStarted
        resetFeedState()
        if (wasStarted) startSubscriptions()
    }

    private class FeedSync(
        val coordinator: RelayFeedHistoryCoordinator,
        val since: Long?,
        val until: Long,
    ) {
        var addedCount = 0
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
                when (signal) {
                    is SubscriptionSignal.Event -> appendFeedEvent(signal.event)
                    // 再送 REQ がリレーの件数上限で切られた区間を、そのリレーだけから取り直す。
                    // 購読の停止で止まるよう、購読のジョブとして走らせる。
                    is SubscriptionSignal.Resumed -> subscriptionJobs += launch { recoverLiveGap(signal) }
                    else -> Unit
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
        updateEvents { _ ->
            rawEvents.values
                .filter { !isFiltered(it) }
                .let(::sortTimelineEvents)
        }
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
        updateEvents { current -> sortTimelineEvents(current.events) }
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
        if (!isAtTop) {
            val cur = currentFeedState()
            // 過去ページ(loadMore)も同じ経路で流れ込むため、現在の最上部より新しいものだけを新着として数える。
            val newestSortTime = cur.events.firstOrNull()?.let { eventSortTimes[it.id] ?: it.createdAt }
            val newCount = if (newestSortTime == null) 0 else additions.count {
                (eventSortTimes[it.id] ?: it.createdAt) > newestSortTime
            }
            if (newCount > 0) {
                setFeedState(cur.copy(newPostCount = cur.newPostCount + newCount), immediate = false)
            }
        }
        updateEvents(immediate = true) { current -> sortTimelineEvents(current.events + additions) }
    }

    private val updateEventsMutex = Mutex()

    /**
     * [computeEvents] はロック取得後・メインスレッド側で評価すること。直前の呼び出しが適用済みの
     * 最新[UiState]を渡すので、呼び出し元で事前に計算したイベントリストをそのまま渡すのではなく、
     * ここで初めて最新状態から導出する。そうしないと、後発の呼び出しが古いイベントリストで
     * 上書きしてしまい、削除・フィルタ済みのノートが復活する競合を防げない。
     */
    private fun updateEvents(
        immediate: Boolean = false,
        computeEvents: (current: UiState) -> List<NostrEvent>,
    ) {
        // filterKeys・extractNpubReferencesによる全件走査はコストが大きいため、
        // メインスレッドを塞がずバックグラウンドで計算する。呼び出しはMutexで直列化し、
        // 各呼び出しが必ず直前の呼び出しの適用結果(setFeedState済み)を踏まえて
        // currentFeedState()を取得するようにする。
        //
        // UNDISPATCHED: Mutexが空いていれば呼び出し元と同じフレームで同期的に進む。
        // テスト用のUnconfinedディスパッチャーと組み合わせると余分なスケジューリングホップが
        // 生まれず、本番のDispatchers.Defaultでは正しく別スレッドへディスパッチされる。
        launch(start = CoroutineStart.UNDISPATCHED) {
            updateEventsMutex.withLock {
                while (!closed) {
                    val revision = feedStateRevision
                    val oldestVisibleAt = historyRevealOldestAt
                    val current = currentFeedState()
                    val events = computeEvents(current)
                    val ownPubkeySnapshot = ownPubkey
                    val eventSortTimesSnapshot = eventSortTimes.toMap()
                    val newState = withContext(computeDispatcher) {
                        computeUpdatedFeedState(
                            events = events,
                            oldestVisibleAt = oldestVisibleAt,
                            current = current,
                            ownPubkeySnapshot = ownPubkeySnapshot,
                            eventSortTimesSnapshot = eventSortTimesSnapshot,
                        )
                    }

                    // バックグラウンド計算中にプロフィールやリアクションなどが更新された場合、
                    // 古いUiStateをコミットするとその更新を巻き戻してしまう。最新状態から再計算する。
                    if (feedStateRevision != revision) continue

                    if (newState.events.isNotEmpty()) {
                        initialFeedSlowJob?.cancel()
                        initialFeedSlowJob = null
                    }
                    setFeedState(newState, immediate = immediate)
                    break
                }
            }
        }
    }

    // FeedControllerの可変フィールドを読み書きしない純粋関数。Dispatchers.Default上から呼ばれる。
    // ただしfeedItemMapper/mentionedPubkeysCacheはevent ID単位の結果キャッシュであり、
    // 常にupdateEventsMutex配下で直列に呼ばれるため例外として直接参照する。
    private fun computeUpdatedFeedState(
        events: List<NostrEvent>,
        oldestVisibleAt: Long?,
        current: UiState,
        ownPubkeySnapshot: String?,
        eventSortTimesSnapshot: Map<String, Long>,
    ): UiState {
        val visibleEvents = events
            .let { timelineEvents ->
                oldestVisibleAt?.let { oldest ->
                    timelineEvents.filter { event ->
                        (eventSortTimesSnapshot[event.id] ?: event.createdAt) >= oldest
                    }
                } ?: timelineEvents
            }
            .take(MAX_TIMELINE_EVENTS)
        val visibleEventIds = visibleEvents.mapTo(linkedSetOf()) { it.id }
        val retainedEventIds = visibleEventIds + visibleEvents.mapNotNull { it.replyTargetId() } +
            visibleEvents.flatMap { quotedEventIds(it) }
        val quotedEvents = current.quotedEvents.filterKeys { it in retainedEventIds }
        val replies = current.replies.filterKeys { it in visibleEventIds }
        val quoteRepostEvents = current.quoteRepostEvents.filterKeys { it in visibleEventIds }
        val retainedPubkeys = buildSet {
            visibleEvents.forEach { event ->
                add(event.pubkey)
                addAll(mentionedPubkeysCache.mentionedPubkeys(event))
            }
            quotedEvents.values.forEach { event ->
                add(event.pubkey)
                addAll(mentionedPubkeysCache.mentionedPubkeys(event))
            }
            replies.values.flatten().forEach { event ->
                add(event.pubkey)
                addAll(mentionedPubkeysCache.mentionedPubkeys(event))
            }
            quoteRepostEvents.values.flatten().forEach { event ->
                add(event.pubkey)
                addAll(mentionedPubkeysCache.mentionedPubkeys(event))
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
            ownPubkeySnapshot?.let(::add)
        }
        val profiles = current.profiles.filterKeys { it in retainedPubkeys } +
            ProfileRepository.getCached(retainedPubkeys)
        val parsedContents = feedItemMapper.map(visibleEvents)

        return current.copy(
            events = visibleEvents,
            parsedContents = parsedContents,
            isInitialLoad = if (visibleEvents.isNotEmpty()) false else current.isInitialLoad,
            initialFeedState = if (visibleEvents.isNotEmpty()) {
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
            quoteRepostEvents = quoteRepostEvents,
            quotedEvents = quotedEvents,
            repostedByPubkeys = current.repostedByPubkeys.filterKeys { it in visibleEventIds },
            likedReactions = current.likedReactions.filterKeys { it in retainedEventIds },
            ownEmojiReactionEventIds = current.ownEmojiReactionEventIds
                .filterKeys { it in retainedEventIds },
            repostedEvents = current.repostedEvents.filterKeys { it in retainedEventIds },
        )
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
        val activeBatches = engagementHistoryDedups.values.filter { targetId in it.eventIds }
        if (activeBatches.isNotEmpty() && activeBatches.all { !it.seenKeys.add(batchKey) }) {
            return false
        }
        return rememberSeenId(seenIds, globalKey)
    }

    private fun hasRevealableEvents(): Boolean {
        val floor = historyRevealOldestAt ?: Long.MIN_VALUE
        return rawEvents.values.any { event ->
            (eventSortTimes[event.id] ?: event.createdAt) >= floor && !isFiltered(event)
        }
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
        // ライブ購読は新しい投稿に絞る。既存分の取得は履歴フェッチが担うため、スクロールで古い投稿が
        // 増えてもライブ側のREQは変わらない(全件を都度送り直すとREQが肥大化する)。
        val liveIds = liveEngagementEventIds(ids)
        val filters = engagementFilters(liveIds, since = liveSince)
        val existing = engagementSession
        if (existing != null) {
            // 履歴取得の完了ごとに呼ばれるが、監視IDも取得先も同じなら since が違うだけの再送になる。
            if (liveIds.toSet() != engagementLiveEventIds?.toSet() || targetRelays != previousRelays) {
                existing.update(filters, relayTarget)
            }
            engagementLiveEventIds = liveIds
        } else {
            engagementLiveEventIds = liveIds
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
        val workByRelay = targetRelays.sorted().mapNotNull { relayUrl ->
            if (relayUrl in engagementHistorySessions) return@mapNotNull null
            if (engagementRetryJobs[relayUrl]?.isActive == true) return@mapNotNull null
            if ((engagementFailureCounts[relayUrl] ?: 0) > MAX_ENGAGEMENT_HISTORY_RETRIES) {
                return@mapNotNull null
            }
            nextEngagementHistoryWork(backfillIds, setOf(relayUrl))
        }
        workByRelay.forEach { work ->
            startEngagementHistoryFetch(subIds = subIds, work = work, until = cutoff)
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
            engagementHistoryDedups[work.relayUrl] = EngagementHistoryDedup(eventIds.toSet())
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
            engagementEventMutex.withLock { engagementHistoryDedups.remove(work.relayUrl) }
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
            engagementEventMutex.withLock { engagementHistoryDedups.remove(work.relayUrl) }
            eventIds.forEach { eventId ->
                if (engagementHistoryStates[eventId] == EngagementHistoryState.InFlight) {
                    engagementHistoryStates[eventId] = EngagementHistoryState.Partial
                }
            }
            session.close()
            return
        }
        engagementHistorySessions[work.relayUrl] = session
        var closedDisposition: RetryDisposition? = null
        subscriptionJobs += launch {
            session.signals.collect { signal ->
                if (engagementHistorySessions[work.relayUrl] !== session) return@collect
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
                                } else {
                                    // 認証必須・ブロック・単一フィルターでの拒否は、再送しても同じ結果になる。
                                    // このままだと Partial のまま直ちに再選択されて REQ が繰り返されるため、
                                    // 次の復帰(retryPartialHistory が失敗回数を戻す)まで、このリレーへの取得を止める。
                                    engagementFailureCounts[work.relayUrl] = MAX_ENGAGEMENT_HISTORY_RETRIES + 1
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
                            engagementHistoryDedups.remove(work.relayUrl)
                        }
                        engagementHistorySessions.remove(work.relayUrl)
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
                engagementHistoryStates[eventId] != EngagementHistoryState.Complete &&
                    missingEngagementPartitions(eventId, relayUrl).isNotEmpty()
            } ?: return@forEach
            val missing = missingEngagementPartitions(firstEventId, relayUrl)
            val partitions = if (relayUrl in isolatedEngagementRelays) {
                setOf(missing.first())
            } else {
                missing
            }
            val batch = eventIds.asSequence()
                .filter { engagementHistoryStates[it] != EngagementHistoryState.Complete }
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

    /** 新しい順(タイムライン上の並び時刻)に [MAX_LIVE_ENGAGEMENT_EVENTS] 件まで。時刻不明は新着とみなす。 */
    private fun liveEngagementEventIds(ids: List<String>): List<String> {
        if (ids.size <= MAX_LIVE_ENGAGEMENT_EVENTS) return ids
        return ids
            .sortedByDescending { eventSortTimes[it] ?: Long.MAX_VALUE }
            .take(MAX_LIVE_ENGAGEMENT_EVENTS)
    }

    private fun engagementFilters(
        ids: List<String>,
        since: Long? = null,
        until: Long? = null,
    ): List<NostrFilter> {
        // kind 以外が同一の #e 系(リアクション・kind 1 返信・リポスト)は1本にまとめる。
        // 別々に送るとID一覧を3回送ることになり、REQ が大きくなる。
        val mergedEventTagPartitions = setOf(
            EngagementPartition.Reaction,
            EngagementPartition.KindOneReply,
            EngagementPartition.Repost,
        )
        val merged = NostrFilter(
            kinds = mergedEventTagPartitions.flatMap { it.filter(ids).kinds.orEmpty() },
            eTags = ids,
            since = since,
            until = until,
        )
        return listOf(merged) + EngagementPartition.entries
            .filterNot { it in mergedEventTagPartitions }
            .map { it.filter(ids, since, until) }
    }

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
            EngagementAccumulator.quoteReposts(currentFeedState(), targetIds, event),
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
            reaction = "reac-$suffix",
            repostTarget = "rpt-$suffix",
            quote = "quot-$suffix",
        )
    }

    companion object {
        private val DISPLAY_EVENT_KINDS = linkedSetOf(1, COMMENT_EVENT_KIND)
        private const val FEED_PAGE_SIZE = 30
        /** 差分取得でこれを超えて新着が増えたら打ち切る。表示上限 (800 件) より十分小さく取る。 */
        private const val MAX_FEED_SYNC_EVENTS = 500
        private const val ALL_RELAYS_SYNC_KEY = ""
        /** ライブ配信の途切れを取り直すとき、配送遅れの投稿を拾うために遡る秒数。 */
        private const val LIVE_RESUME_OVERLAP_SECONDS = 60L
        private const val MAX_HISTORY_PAGE_SIZE = 3_840
        // FeedItemMapperのデフォルトのキャッシュ上限がこの値を下回らないよう、そちらから直接参照する。
        internal const val MAX_TIMELINE_EVENTS = 800
        private const val MAX_TRACKED_ENGAGEMENT_EVENTS = 100
        private const val MAX_LIVE_ENGAGEMENT_EVENTS = 40
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
    val reaction: String,
    val repostTarget: String,
    val quote: String,
)
