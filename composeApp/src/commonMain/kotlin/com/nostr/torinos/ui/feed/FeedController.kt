package com.nostr.torinos.ui.feed

import com.nostr.torinos.ui.feed.FeedViewModel.UiState
import com.nostr.torinos.util.appLog
import com.nostr.torinos.util.engagementTraceLog
import com.nostr.torinos.util.isEngagementTraceEnabled
import com.nostr.torinos.util.isFeedTraceEnabled
import com.nostr.torinos.ui.feed.FeedViewModel.InitialFeedState
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.engagement.EngagementAction
import com.nostr.torinos.engagement.EngagementOperationId
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.NoteEngagementCommand
import com.nostr.torinos.engagement.EngagementReducer
import com.nostr.torinos.engagement.EngagementStore
import com.nostr.torinos.engagement.PendingEngagementOperation
import com.nostr.torinos.engagement.PendingEngagementRegistry
import com.nostr.torinos.engagement.TargetEngagement
import com.nostr.torinos.engagement.scopedTo
import com.nostr.torinos.engagement.toNoteEngagementState
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.engagement.summarizeEngagement
import com.nostr.torinos.engagement.ReactionEventReducer
import com.nostr.torinos.engagement.ReactionRefreshFetcher
import com.nostr.torinos.engagement.NoteTarget
import com.nostr.torinos.engagement.displayOwnEmojiReactionEventIds
import com.nostr.torinos.engagement.isRepostedByMe
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.REPORT_EVENT_KIND
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.isSupportedTimelineComment
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.reportNoteTags
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

internal object RepositoryFeedSubscriptionGateway : FeedSubscriptionGateway {
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
    private val historyStore: FeedHistoryStore = FeedHistoryStore(),
    /** 付随情報のイベント集合。画面間で共有する。テストでは個別のものを渡す。 */
    private val engagementStore: EngagementStore = EngagementStore.shared,
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
    private val trace = FeedTrace(
        "feed#$instanceKey(${authorPubkey?.take(8) ?: authorPubkeys?.let { "authors=${it.size}" } ?: "global"}" +
            "${relayUrl?.let { " relay=$it" } ?: ""}${hashtag?.let { " #$it" } ?: ""})",
        onChanged = { scheduleTraceDump() },
    )
    private var traceDumpJob: Job? = null
    private val subscriptionJobs = mutableListOf<Job>()
    private val lifecycleJobs = mutableListOf<Job>()
    private var subscriptionIds: SubscriptionIds? = null
    private var liveSession: SubscriptionSession? = null
    private var relayHistoryCoordinator: RelayFeedHistoryCoordinator? = null
    private val fetchArbiter = RelayFetchArbiter()
    private var historyScopeGeneration = 0
    private var latestHistoryState: RelayHistoryUiState? = null
    private var windowAnchorId: String? = null
    /**
     * 差分取得。途中でタブを離れても再開して最後まで取りきる。
     * 復帰・手動更新は全リレー ([ALL_RELAYS_SYNC_KEY])、ライブ配信の途切れはそのリレーだけを対象にし、
     * 対象ごとに 1 本だけ持つ。新しい差分取得は未完了分の範囲も引き継ぐ。
     */
    private val feedSyncs = mutableMapOf<String, FeedSync>()
    private var feedSyncSequence = 0
    /** 手動更新を過去ページの取得で処理しているとき、その完了で更新表示を消す。 */
    private var historyOwnsRefresh = false
    private var subscriptionGeneration = 0
    private var historyRequestGeneration = 0
    private val requestedProfilePubkeys = mutableSetOf<String>()
    private var refreshIndicatorTimeoutJob: Job? = null
    private var initialFeedSlowJob: Job? = null
    private val pendingOperations = PendingEngagementRegistry.forSession(accountSession?.sessionId)
    /**
     * 付随情報の取得。本番では取得先リレーごとに共有し、ポスト詳細と同じ投稿を重ねて取りにいかない。
     * テストの購読口を渡したときは、このフィード専用のものを使う。
     */
    private val engagementFetcher: EngagementFetcher =
        (relayUrl?.let(RelayTarget::Single) ?: RelayTarget.AllEnabled).let { target ->
            if (subscriptions === RepositoryFeedSubscriptionGateway) {
                SharedEngagementFetchers.forTarget(target)
            } else {
                EngagementFetcher(
                    scope = scope,
                    subscriptions = subscriptions,
                    relayTarget = target,
                    store = engagementStore,
                    subscriptionIdPrefix = "reac-$shortKey-$instanceKey",
                    log = { message -> engagementLog(message) },
                )
            }
        }
    /** タイムラインに出すリポストの重複排除。付随情報の件数には使わない（件数はストアから導出する）。 */
    private val receivedRepostEvents = linkedMapOf<String, NostrEvent>()
    /** 保持中の投稿。付随情報を残す範囲と表示待ちの判定に使う値も、出し入れに合わせて更新する。 */
    private val rawEvents = HeldFeedEvents(Int.MAX_VALUE) { event -> eventSortTimes[event.id] ?: event.createdAt }
    private val canonicalEvents = linkedMapOf<String, NostrEvent>()
    private val eventSortTimes = mutableMapOf<String, Long>()
    private val pendingQuoteIds = linkedSetOf<String>()
    /**
     * 履歴ページで受信済みだが、ページの確定前でまだ保持していない投稿。付随情報の取得は受信時に始まるので、
     * 確定前に届いた件数を組み直しで捨てないよう、この間も保持中と同じく残す。
     */
    private val uncommittedHistoryEventIds = linkedSetOf<String>()
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
        engagementFetcher.register(
            owner = this,
            sortTimeOf = { eventSortTimes[it] },
            onEvent = { event ->
                scheduleProfileFetch(event.pubkey)
                if (event.kind == 1 || event.kind == COMMENT_EVENT_KIND) scheduleMentionedProfileFetch(event.content)
            },
        )
        if (isWriteSupported) {
            lifecycleJobs += launch {
                ownPubkey = accountSession?.pubkey
                projectEngagement(engagementFetcher.trackedBy(this@FeedController).toList())
            }
        }
        lifecycleJobs += launch {
            pendingOperations.changes.collect { changed ->
                projectEngagement(changed.filter { engagementFetcher.isTrackedBy(this@FeedController, it) })
            }
        }
        lifecycleJobs += launch {
            engagementStore.changes.collect { changed ->
                projectEngagement(changed.filter { engagementFetcher.isTrackedBy(this@FeedController, it) })
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
                engagementFetcher.onTargetRelaysChanged(targetRelays)
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
        historyStore.remove(eventId)
        pendingTimelineEvents.remove(eventId)
        // 組み直しは保持中の投稿を基準に付随情報を残すので、先に保持から外す。
        rawEvents.remove(eventId)
        canonicalEvents.remove(eventId)
        eventSortTimes.remove(eventId)
        forgetEngagementHistory(eventId)
        updateEvents(immediate = true) { current -> current.events.filter { it.id != eventId } }
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
        val operationId = EngagementOperationId("feed-$instanceKey-${++nextEngagementOperationId}")
        val before = currentFeedState().noteEngagement(eventId)
        val optimistic = engagementCoordinator.begin(before, operationId, request)
        if (optimistic == before) return
        // 送信中の操作はアカウントで共有する。ポスト詳細を開いても同じ表示になる。
        optimistic.pendingOperations[request.slot]?.let { pendingOperations.put(eventId, request.slot, it) }
        updateFeedState { it.withEngagement(eventId, optimistic).copy(engagementError = null) }
        launch {
            var committed = false
            var failure: Throwable? = null
            try {
                val published = engagementCoordinator.execute(command).getOrThrow()
                // 送ったイベント（リアクション・リポスト・取り消しの kind 5）をストアへ入れてから送信中の操作を外す。
                // 件数はストアの集合から導出し直すので、同じイベントが後からリレーで届いても重ならない。
                engagementStore.observe(published, null)
                committed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            } finally {
                pendingOperations.remove(eventId, operationId)
                projectEngagement(listOf(eventId))
            }
            if (failure != null) updateFeedState { it.copy(engagementError = failureMessage) }
        }
    }

    fun reportEvent(event: NostrEvent, reason: String, detail: String) {
        accountSession?.muteStore?.mute(event.pubkey)
        rawEvents.invalidateRevealable()
        rebuildFilteredEvents()
        launch {
            signedEventPublisher.publish(
                detail,
                REPORT_EVENT_KIND,
                reportNoteTags(event, reason),
            )
        }
    }

    fun loadMore(explicit: Boolean = true) {
        if (closed) return
        if (shiftStoredWindow(older = true)) {
            trace.log { "loadMore(explicit=$explicit) 保持済みの古い投稿へ表示窓を移動（通信なし）" }
            return
        }
        val current = currentFeedState()
        if (!explicit && current.requiresExplicitLoadMore) {
            trace.log { "loadMore(explicit=false) 自動読み込みは停止中（明示操作待ち）" }
            return
        }
        trace.log {
            "loadMore(explicit=$explicit) canLoadMore=${current.canLoadMore} isLoadingMore=${current.isLoadingMore} " +
                "coordinator=${relayHistoryCoordinator != null}"
        }
        trace.count(if (explicit) "loadMore.explicit" else "loadMore.auto")
        if (current.canLoadMore) relayHistoryCoordinator?.loadMore()
    }

    fun loadNewerStored() { shiftStoredWindow(older = false) }

    fun resetHistory() {
        trace.log { "resetHistory started=$subscriptionsStarted" }
        val wasStarted = subscriptionsStarted
        resetFeedState()
        if (wasStarted) startSubscriptions()
    }

    fun retryHistory() {
        trace.log { "retryHistory syncs=${feedSyncs.size}" }
        relayHistoryCoordinator?.retryStalledRelays()
        feedSyncs.values.toList().forEach {
            it.budgetReached = false
            it.addedCount = 0
            it.coordinator.resume()
            it.coordinator.retryStalledRelays()
        }
    }

    private fun shiftStoredWindow(older: Boolean): Boolean {
        val current = currentFeedState()
        if (older && !current.hasOlderStoredEvents || !older && !current.hasNewerStoredEvents) return false
        val all = revealedEvents()
        val start = current.events.firstOrNull()?.id?.let { id -> all.indexOfFirst { it.id == id } } ?: 0
        val next = if (older) start + MAX_TIMELINE_EVENTS / 2 else (start - MAX_TIMELINE_EVENTS / 2).coerceAtLeast(0)
        val anchor = all.getOrNull(next)?.id ?: return false
        // 先頭へ戻ったら固定を外し、以後の新着を一覧の先頭に出す。
        windowAnchorId = if (next == 0) null else anchor
        feedStateRevision++
        rebuildFilteredEvents(immediate = true)
        return true
    }

    private fun revealedEvents(): List<NostrEvent> = sortTimelineEvents(rawEvents.values.filter { !isFiltered(it) })
        .filter { (eventSortTimes[it.id] ?: it.createdAt) >= (historyRevealOldestAt ?: Long.MIN_VALUE) }

    private fun publishHistoryStatus() {
        val syncStates = feedSyncs.values.mapNotNull { it.historyState }
        val history = latestHistoryState
        updateFeedState { it.copy(
            pendingGapCount = syncStates.sumOf { state -> state.unfinishedFilterCount },
            coverageGapCount = (history?.coverageGapCount ?: 0) + syncStates.sumOf { state -> state.coverageGapCount },
            stalledRelayCount = (history?.stalledRelayCount ?: 0) + syncStates.sumOf { state -> state.stalledRelayCount },
            awaitingAuthRelayCount = (history?.awaitingAuthRelayCount ?: 0) + syncStates.sumOf { state -> state.awaitingAuthRelayCount },
            storageFailure = history?.storageFailure == true || syncStates.any { state -> state.storageFailure },
            localHistoryLimitReached = historyStore.limitReached,
            requiresExplicitLoadMore = history?.requiresExplicitLoadMore == true,
        ) }
    }

    fun refresh() {
        if (currentFeedState().isRefreshing) {
            trace.log { "refresh 更新中のため無視" }
            return
        }
        trace.log { "refresh" }
        trace.count("refresh")
        launch {
            // 古い保存済みウィンドウを見ていても、更新後は最新から表示する。
            if (windowAnchorId != null) {
                windowAnchorId = null
                feedStateRevision++
                rebuildFilteredEvents(immediate = true)
            }
            setFeedState(currentFeedState().copy(isRefreshing = true, isLoadingMore = false))
            scheduleRefreshIndicatorTimeout()
            manualRefreshRequested = true
            stopSubscriptions(clearRefreshing = false)
            startSubscriptions()
        }
    }

    fun refreshReactions(eventId: String) {
        if (!engagementFetcher.isTrackedBy(this, eventId)) return
        launch {
            val events = ReactionRefreshFetcher.fetch(
                subscriptionId = "feed-reaction-refresh-$instanceKey-${++nextReactionRefreshId}",
                eventId = eventId,
                target = relayTarget,
            )
            events.forEach { event ->
                handleEngagementEvent(event)
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
        engagementFetcher.reset(this)

        pendingTimelineEvents.clear()
        requestedProfilePubkeys.clear()
        syncEngagementRetention()
        uncommittedHistoryEventIds.clear()
        receivedRepostEvents.clear()
        rawEvents.clear()
        canonicalEvents.clear()
        eventSortTimes.clear()
        pendingQuoteIds.clear()
        pendingRepostTargets.clear()

        relayHistoryCoordinator?.close()
        relayHistoryCoordinator = null
        closeFeedSyncs()
        historyScopeGeneration++
        historyStore.replaceScope(historyScopeGeneration)
        latestHistoryState = null
        windowAnchorId = null
        historyOwnsRefresh = false

        historyRevealOldestAt = null
        initialHistoryRequested = false
        manualRefreshRequested = false
        feedStateRevision++
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
        engagementFetcher.activate(this)

        // 引用先イベント受信（nostr:note/nevent または q タグ）
        subscriptionJobs += launch {
            subscriptions.events(ids.quote).collect { event ->
                if (event.kind !in DISPLAY_EVENT_KINDS) return@collect
                if (event.kind == COMMENT_EVENT_KIND && !event.isSupportedTimelineComment()) return@collect
                val cur = currentFeedState()
                if (cur.quotedEvents.containsKey(event.id)) {
                    trace.count("quote.recvDup")
                    return@collect
                }
                trace.count("quote.recv")
                pendingQuoteIds.remove(event.id)
                setFeedState(cur.copy(quotedEvents = cur.quotedEvents + (event.id to event)), immediate = false)
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
            }
        }

        // ミュート・NGワード変更時にフィルタ済みリストを再構築
        if (filterMutedUsers) {
            subscriptionJobs += launch {
                accountSession?.muteStore?.mutedPubkeys?.collect {
                    rawEvents.invalidateRevealable()
                    rebuildFilteredEvents()
                }
            }
        }
        subscriptionJobs += launch {
            accountSession?.ngWordStore?.ngWords?.collect {
                rawEvents.invalidateRevealable()
                rebuildFilteredEvents()
            }
        }

        // content が空のリポストから元ポストを追加取得
        subscriptionJobs += launch {
            subscriptions.events(ids.repostTarget).collect { event ->
                if (event.kind !in DISPLAY_EVENT_KINDS) return@collect
                if (event.kind == COMMENT_EVENT_KIND && !event.isSupportedTimelineComment()) return@collect
                val pending = pendingRepostTargets.remove(event.id) ?: run {
                    trace.count("repostTarget.recvDup")
                    return@collect
                }
                trace.count("repostTarget.recv")
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
                // 初回のみ履歴ページを取得。読み込み失敗後の手動更新もここを通る。
                val refreshing = manualRefreshRequested
                manualRefreshRequested = false
                trace.log { "start: 初回の履歴取得 (refresh=$refreshing) gen=$subscriptionGeneration" }
                startRelayHistory(forRefresh = refreshing)
            } else if (manualRefreshRequested && relayHistoryCoordinator == null) {
                manualRefreshRequested = false
                trace.log { "start: 履歴なしの手動更新 → 履歴取得し直し gen=$subscriptionGeneration" }
                startRelayHistory(forRefresh = true)
                engagementFetcher.resubscribe(retryPartialHistory = true)
            } else {
                // タブ再表示・手動更新では、読み込み済みの過去ページを保ったまま
                // ライブ購読を再開し、離れていた間の投稿を取りきる。
                val refreshing = manualRefreshRequested
                manualRefreshRequested = false
                relayHistoryCoordinator?.resume()
                // 手動更新は、拒否や再試行待ちで止まっているリレーにもすぐ問い合わせ直す。
                if (refreshing) relayHistoryCoordinator?.retryStalledRelays()
                val nowSec = Clock.System.now().epochSeconds
                // 時計のずれた未来の投稿を基準にすると、差分を取り損ねる。
                val gapSince = eventSortTimes.values.filter { it <= nowSec }.maxOrNull()
                val strategy = resumeSyncStrategy(gapSince = gapSince, nowSec = nowSec)
                val startsAllSync = strategy != ResumeSyncStrategy.None || refreshing
                trace.log {
                    "start: 再開 gen=$subscriptionGeneration refresh=$refreshing strategy=$strategy " +
                        "gap=${gapSince?.let { nowSec - it }}s 既存sync=${feedSyncs.keys.map { it.ifEmpty { "ALL" } }} " +
                        "held=${rawEvents.values.size}"
                }
                // 全リレーの差分取得を作り直すなら、古いものは再開せずに引き継ぐ（無駄な REQ を送らない）。
                feedSyncs.filterKeys { !startsAllSync || it != ALL_RELAYS_SYNC_KEY }
                    .values.toList()
                    .forEach {
                    if (!it.budgetReached) {
                        it.coordinator.resume()
                        it.coordinator.retryStalledRelays()
                    }
                }
                subscribeLiveFeed(since = nowSec)
                if (startsAllSync) {
                    startFeedSync(
                        since = if (strategy == ResumeSyncStrategy.LatestPage) null else gapSince,
                        until = nowSec,
                    )
                }
                engagementFetcher.resubscribe(retryPartialHistory = true)
            }
        }
    }

    fun stopSubscriptions(clearRefreshing: Boolean = true) {
        if (!subscriptionsStarted) return
        trace.log { "stop gen=$subscriptionGeneration" }
        trace.dump()
        subscriptionsStarted = false
        engagementFetcher.deactivate(this)
        val ids = subscriptionIds
        subscriptionIds = null
        subscriptionJobs.forEach { it.cancel() }
        subscriptionJobs.clear()
        initialFeedSlowJob?.cancel()
        initialFeedSlowJob = null
        // 過去ページのカーソルは復帰後の「もっと読む」に必要なので、通信だけ止める。
        relayHistoryCoordinator?.pause()
        feedSyncs.values.forEach { it.coordinator.pause() }
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
        val sessionsToClose = listOfNotNull(liveSession)
        liveSession = null
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
        trace.log { "close" }
        stopSubscriptions()
        engagementFetcher.release(this)
        engagementStore.release(this)
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
        historyOwnsRefresh = false
        val historyFloor = Clock.System.now().epochSeconds
        initialHistoryRequested = true
        subscribeLiveFeed(since = historyFloor)
        val targetRelays = subscriptions.targetRelayUrls(relayTarget)
        // 初回は表示境界が決まるまで過去分を出さない。先に返ったリレーの深い投稿を
        // 一度表示してから引っ込める、ちらつきを避けるため。
        if (historyRevealOldestAt == null) historyRevealOldestAt = historyFloor
        scheduleInitialFeedSlowState()
        val pageScope = historyScopeGeneration
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
            onEvent = ::prepareHistoryEvent,
            onPageCommit = { url, page -> commitHistoryPage(pageScope, url, page) },
            arbiter = fetchArbiter,
            canRequest = { !historyStore.limitReached },
            onReveal = { floor ->
                revealHistoryThrough(floor)
                flushPendingTimelineEvents()
            },
            onFlush = ::flushPendingTimelineEvents,
            onState = { history ->
                latestHistoryState = history
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
                publishHistoryStatus()
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
        trace.log { "history start floor=$historyFloor relays=${targetRelays.size} filters=${feedFilters().size}" }
        coordinator.updateRelays(targetRelays)
        // 登録時の状態通知で消さないよう、取得を始める直前に手動更新の表示を引き受ける。
        historyOwnsRefresh = forRefresh
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

    private fun prepareHistoryEvent(event: NostrEvent) {
        // 履歴ページの表示はcommit後。付随情報の取得は遅いリレーの完了を待たない。
        if (event.kind != 1 && event.kind != COMMENT_EVENT_KIND) return
        if (event.kind == COMMENT_EVENT_KIND && !event.isSupportedTimelineComment()) return
        // 表示しない返信は、commit 時に返信先の取得だけを行う（appendFeedEvent と同じ扱い）。
        if (!includeRepliesInFeed && event.replyTargetId() != null) return
        uncommittedHistoryEventIds.remove(event.id)
        uncommittedHistoryEventIds += event.id
        // 確定しないまま捨てられたページの分が溜まり続けないよう、古いものから外す。
        while (uncommittedHistoryEventIds.size > MAX_UNCOMMITTED_HISTORY_EVENTS) {
            uncommittedHistoryEventIds.remove(uncommittedHistoryEventIds.first())
        }
        scheduleEngagementFetch(event.id)
    }

    private fun commitHistoryPage(
        pageScope: Int,
        url: String,
        page: Map<Int, List<NostrEvent>>,
        liveGap: Boolean = false,
    ): FeedPageCommitResult {
        val stored = page.flatMap { (key, events) -> events.map { StoredFeedEvent(it, setOf(url), setOf(key)) } }
        return when (historyStore.commit(pageScope, stored)) {
            FeedStoreCommitResult.RejectedStaleScope -> FeedPageCommitResult.RejectedStaleScope
            // 保存に失敗したページは再試行で同じ投稿を確定し直すので、受信済みの付随情報は残しておく。
            FeedStoreCommitResult.Failed -> FeedPageCommitResult.Failed
            FeedStoreCommitResult.Committed -> {
                var added = 0
                var visibleAdded = 0
                if (isFeedTraceEnabled) traceReceived(if (liveGap) "gap" else "page", stored.map { it.event })
                stored.forEach { source ->
                    val count = if (liveGap) appendFeedEvent(source.event) else appendHistoryEvent(source.event)
                    if (count == 0) return@forEach
                    added += count
                    // 表示0件ページの自動継続は、ミュート・NGワードで隠れた投稿を表示済みと数えない。
                    val display = rawEvents[sourceDisplayId(source.event)]
                    if (display != null && !isFiltered(display)) visibleAdded += count
                }
                // 保持へ移したので、以後は保持中の投稿として付随情報が残る。
                stored.forEach { uncommittedHistoryEventIds.remove(it.event.id) }
                FeedPageCommitResult.Committed(visibleAdded = visibleAdded, added = added)
            }
        }
    }

    private fun appendLiveEvent(event: NostrEvent, url: String) {
        if (isFeedTraceEnabled) traceReceived("live", listOf(event))
        val source = StoredFeedEvent(event, setOf(url), emptySet())
        if (historyStore.commit(historyScopeGeneration, listOf(source)) != FeedStoreCommitResult.Committed) {
            updateFeedState { it.copy(storageFailure = true) }
            return
        }
        appendFeedEvent(event)
        if (historyStore.limitReached) {
            // 各表示投稿の代表sourceを保護する。多数の同一対象リポストでも保護集合を無制限にしない。
            val displayIds = currentFeedState().events.mapTo(mutableSetOf()) { it.id }
            displayIds += event.id
            val sources = historyStore.snapshot
            val protected = sources.groupBy { source -> sourceDisplayId(source.event) }
                .filterKeys { it in displayIds }.values.mapNotNull { group -> group.maxByOrNull { it.event.createdAt }?.event?.id }.toSet()
            historyStore.trimLive(protected)
            val retained = historyStore.snapshot.mapTo(mutableSetOf()) { sourceDisplayId(it.event) }
            rawEvents.values.toList().filter { it.id !in retained }.forEach { removed ->
                rawEvents.remove(removed.id)
                eventSortTimes.remove(removed.id)
                pendingTimelineEvents.remove(removed.id)
                forgetEngagementHistory(removed.id)
            }
        }
        publishHistoryStatus()
    }

    private fun sourceDisplayId(event: NostrEvent): String = if (event.kind == 6) {
        event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
            ?: runCatching { Json.decodeFromString(NostrEvent.serializer(), event.content).id }.getOrNull()
            ?: event.id
    } else event.id

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
        // 未完了区間も包含して取り直す。単なる再試行は既存coordinatorのカーソルから再開する。
        // 長時間の離脱で最新ページだけを取る場合は、補完対象外として保留区間を引き継がない。
        // 失敗の続くリレーで範囲が広がり続けないよう、補完対象期間より古い部分は打ち切る。
        val gapFillFloor = (until - MAX_GAP_FILL_DURATION_SECONDS).coerceAtLeast(0L)
        val effectiveSince = when {
            previous == null || since == null -> since
            else -> {
                val merged = minOf(since, previous.since ?: since)
                val bounded = maxOf(merged, minOf(gapFillFloor, since))
                if (bounded > merged) appLog("[FeedController] pending feed gap truncated at $bounded")
                bounded
            }
        }
        val effectiveUntil = effectiveUntilOf(until, previous)
        trace.log {
            "sync start key=${key.ifEmpty { "ALL" }} since=$since until=$until → effective since=$effectiveSince " +
                "until=$effectiveUntil relays=${targetRelays.size} 引継ぎ=${previous != null}" +
                (previous?.let { " (前回 since=${it.since} until=${it.until} added=${it.addedCount})" } ?: "")
        }
        trace.count("sync.start")
        previous?.coordinator?.close()
        val isLiveGapRecovery = relayUrl != null
        val pageScope = historyScopeGeneration
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
            onEvent = ::prepareHistoryEvent,
            onPageCommit = { url, page ->
                val result = commitHistoryPage(pageScope, url, page, liveGap = isLiveGapRecovery)
                if (result is FeedPageCommitResult.Committed) sync.addedCount += result.added
                result
            },
            arbiter = fetchArbiter,
            canRequest = { !historyStore.limitReached },
            maxFailures = 3,
            onFlush = ::flushPendingTimelineEvents,
            onState = { state ->
                sync.historyState = state
                publishHistoryStatus()
                // 全リレーが一斉に切れても上限を超えないよう、リレー単位の取り直しは動いている分を合算する。
                val overflowed = if (isLiveGapRecovery) {
                    liveGapRecoveryAddedCount() > MAX_FEED_SYNC_EVENTS
                } else {
                    sync.addedCount > MAX_FEED_SYNC_EVENTS
                }
                // 取りきったか、失敗が続くリレーしか残っていなければ終える。応答しないリレーのために
                // 差分取得を残し続けると、次の復帰で範囲が広がり続ける。
                val finished = state.relayCount == 0 || state.pendingRelayCount == 0 ||
                    (effectiveSince == null && state.isInitialFetchSettled)
                // コールバック中に自身を閉じないよう、完了処理は後で行う。
                when {
                    overflowed -> launch { onFeedSyncOverflow(key, sync) }
                    finished -> launch {
                        if (effectiveSince != null && (state.unfinishedFilterCount > 0 || state.coverageGapCount > 0)) {
                            // 通信の終了後もcoordinatorとカーソルを保留し、手動更新・復帰で再試行する。
                            if (key == ALL_RELAYS_SYNC_KEY) clearRefreshIndicator()
                            flushPendingTimelineEvents()
                        } else finishFeedSync(key, sync)
                    }
                }
            },
        )
        sync = FeedSync(coordinator, effectiveSince, effectiveUntil)
        // 引き継いだ取り直しの件数も数え続け、作り直しのたびに上限が戻らないようにする。
        sync.addedCount = previous?.addedCount ?: 0
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
        trace.log {
            "liveGap ${signal.relayUrl} interruptedAt=${signal.interruptedAt} replayOldestAt=${signal.replayOldestAt} " +
                "→ since=$since until=$until${if (since > until) " (範囲なし)" else ""}"
        }
        if (since > until) return
        trace.count("liveGap")
        // 全リレーの差分取得と範囲が重なっても任せない。あちらは失敗の続くリレーを諦めて終わることが
        // あり、そのリレーの区間を誰も取らなくなるため。重複して届いた投稿は ID で除く。
        startFeedSync(since = since, until = until, relayUrl = signal.relayUrl)
    }

    private fun finishFeedSync(key: String, sync: FeedSync) {
        if (feedSyncs[key] !== sync) return
        trace.log { "sync finish key=${key.ifEmpty { "ALL" }} added=${sync.addedCount}" }
        feedSyncs.remove(key)
        sync.coordinator.close()
        flushPendingTimelineEvents()
        publishHistoryStatus()
        if (key == ALL_RELAYS_SYNC_KEY) clearRefreshIndicator()
    }

    private fun closeFeedSyncs() {
        feedSyncs.values.forEach { it.coordinator.close() }
        feedSyncs.clear()
    }

    private fun liveGapRecoveryAddedCount(): Int =
        feedSyncs.filterKeys { it != ALL_RELAYS_SYNC_KEY }.values
            .filter { it.historyState?.unfinishedFilterCount != 0 && !it.budgetReached }
            .sumOf { it.addedCount }

    private fun effectiveUntilOf(until: Long, previous: FeedSync?): Long =
        previous?.let { maxOf(until, it.until) } ?: until

    /**
     * 差分の新着が多すぎるとき、保持上限を超えて読み込み済みの過去ページが押し出されるのを防ぐ。
     * 復帰・手動更新ではユーザーが戻った直後なので最新から読み直し、読んでいる最中に起きる
     * リレー単位の取り直しでは一覧を崩さず、その取り直しだけを打ち切る。
     */
    private fun onFeedSyncOverflow(key: String, sync: FeedSync) {
        if (closed || feedSyncs[key] !== sync) return
        if (key != ALL_RELAYS_SYNC_KEY) {
            appLog("[FeedController] live gap recovery stopped after ${liveGapRecoveryAddedCount()} events")
            feedSyncs.filterKeys { it != ALL_RELAYS_SYNC_KEY }.values.forEach { relaySync ->
                relaySync.budgetReached = true
                relaySync.coordinator.pause()
            }
            publishHistoryStatus()
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
        var budgetReached = false
        var historyState: RelayHistoryUiState? = null
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
            trace.log { "live update since=$since" }
            trace.count("live.update")
            existing.update(filters, relayTarget)
            return
        }
        trace.log { "live open ${ids.feed} since=$since" }

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
                    is SubscriptionSignal.Event -> appendLiveEvent(signal.event, signal.relayUrl)
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
        if (rawEvents[event.id] != null) {
            updateTimelineSortTime(event.id, timelineCreatedAt)
            return 0
        }
        eventSortTimes[event.id] = timelineCreatedAt
        val quoteIds = quotedEventIds(event)
        rawEvents.add(event, quoteIds + listOfNotNull(event.replyTargetId())).forEach { evicted ->
            eventSortTimes.remove(evicted.id)
            forgetEngagementHistory(evicted.id)
        }
        if (event.id !in canonicalEvents) {
            canonicalEvents[event.id] = event
        }
        while (canonicalEvents.size > MAX_SEEN_IDS) canonicalEvents.remove(canonicalEvents.keys.first())
        if (isFiltered(event)) return 0
        rawEvents.noteRevealable(timelineCreatedAt)
        val cur = currentFeedState()
        if (cur.events.any { it.id == event.id } || pendingTimelineEvents.containsKey(event.id)) return 0
        pendingTimelineEvents[event.id] = event
        scheduleTimelineBatch()
        scheduleQuoteFetch(quoteIds)
        event.replyTargetId()?.takeIf { it !in quoteIds }?.let { scheduleQuoteFetch(listOf(it)) }
        return 1
    }

    private fun isFiltered(event: NostrEvent): Boolean {
        if (filterMutedUsers && accountSession?.muteStore?.isMuted(event.pubkey) == true) return true
        return accountSession?.ngWordStore?.matches(event.content) == true
    }

    private fun rebuildFilteredEvents(immediate: Boolean = false) {
        timelineBatchJob?.cancel()
        timelineBatchJob = null
        pendingTimelineEvents.clear()
        updateEvents(immediate = immediate) { _ ->
            rawEvents.values
                .filter { !isFiltered(it) }
                .let(::sortTimelineEvents)
        }
    }

    private fun appendRepostedEvent(repost: NostrEvent): Int {
        if (!includeRepostsInFeed || receivedRepostEvents.containsKey(repost.id)) return 0
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
                trace.count("repostTarget.req")
                trace.count("repostTarget.idsSent", pendingRepostTargets.size)
                trace.log { "repostTarget REQ ${ids.repostTarget} ids=${pendingRepostTargets.size}（未取得分をまとめて再送）" }
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
        // 件数はストアから導出する。リポスト先を追跡し始める前でも、ストアが取っておいて加える。
        engagementStore.observe(repost, null)
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
        rawEvents[eventId]?.takeIf { !isFiltered(it) }?.let { rawEvents.noteRevealable(timelineCreatedAt) }
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
        updateEvents(immediate = true) { sortTimelineEvents(rawEvents.values.filter { !isFiltered(it) }) }
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
                    val windowAnchor = windowAnchorId
                    val current = currentFeedState()
                    val events = computeEvents(current)
                    val ownPubkeySnapshot = ownPubkey
                    val eventSortTimesSnapshot = eventSortTimes.toMap()
                    // ここでは ID の一覧を写すだけにし、集合はバックグラウンドで作る。
                    val heldSnapshot = rawEvents.snapshot()
                    val uncommittedSnapshot = uncommittedHistoryEventIds.toSet()
                    val (newState, heldEventIds) = withContext(computeDispatcher) {
                        val heldEventIds = heldSnapshot.resolve()
                        computeUpdatedFeedState(
                            events = events,
                            oldestVisibleAt = oldestVisibleAt,
                            current = current,
                            ownPubkeySnapshot = ownPubkeySnapshot,
                            eventSortTimesSnapshot = eventSortTimesSnapshot,
                            heldEventIds = heldEventIds,
                            uncommittedEventIds = uncommittedSnapshot,
                            windowAnchor = windowAnchor,
                        ) to heldEventIds
                    }
                    // 投稿の出入りがなければ、次の組み直しでこの集合を使い回す。
                    rawEvents.remember(heldSnapshot, heldEventIds)

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
        /** 保持中の投稿（表示待ち・表示範囲外・除外中を含む）と、それらの返信先・引用先の ID。 */
        heldEventIds: Set<String>,
        /** 履歴ページで受信済みで、確定を待っている投稿の ID。 */
        uncommittedEventIds: Set<String>,
        windowAnchor: String?,
    ): UiState {
        val revealedEvents = events
            .let { timelineEvents ->
                oldestVisibleAt?.let { oldest ->
                    timelineEvents.filter { event ->
                        (eventSortTimesSnapshot[event.id] ?: event.createdAt) >= oldest
                    }
                } ?: timelineEvents
            }
        val start = windowAnchor?.let { id -> revealedEvents.indexOfFirst { it.id == id } }?.coerceAtLeast(0) ?: 0
        val visibleEvents = revealedEvents.drop(start).take(MAX_TIMELINE_EVENTS)
        val visibleEventIds = visibleEvents.mapTo(linkedSetOf()) { it.id }
        val retainedEventIds = visibleEventIds + visibleEvents.mapNotNull { it.replyTargetId() } +
            visibleEvents.flatMap { quotedEventIds(it) }
        // 投稿ごとの付随情報は、まだ表示していない保持中の投稿の分も残す。表示前に捨てると、
        // 後から表示したときにリポスト表示・件数・引用カードが失われ、取り直しもされない。
        // 2 つの集合を結合して作り直さず、どちらかに含まれるかで判定する。
        val isKept: (String) -> Boolean = {
            it in retainedEventIds || it in heldEventIds || it in uncommittedEventIds
        }
        val quotedEvents = current.quotedEvents.filterKeys(isKept)
        val replies = current.replies.filterKeys(isKept)
        val quoteRepostEvents = current.quoteRepostEvents.filterKeys(isKept)
        // プロフィールはキャッシュから引き直せるので、表示中の投稿に関わる分だけ持つ。
        val retainedPubkeys = buildSet {
            visibleEvents.forEach { event ->
                add(event.pubkey)
                addAll(mentionedPubkeysCache.mentionedPubkeys(event))
            }
            quotedEvents.filterKeys { it in retainedEventIds }.values.forEach { event ->
                add(event.pubkey)
                addAll(mentionedPubkeysCache.mentionedPubkeys(event))
            }
            replies.filterKeys { it in visibleEventIds }.values.flatten().forEach { event ->
                add(event.pubkey)
                addAll(mentionedPubkeysCache.mentionedPubkeys(event))
            }
            quoteRepostEvents.filterKeys { it in visibleEventIds }.values.flatten().forEach { event ->
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
            hasOlderStoredEvents = start + visibleEvents.size < revealedEvents.size,
            hasNewerStoredEvents = start > 0,
            parsedContents = parsedContents,
            isInitialLoad = if (visibleEvents.isNotEmpty()) false else current.isInitialLoad,
            initialFeedState = if (visibleEvents.isNotEmpty()) {
                InitialFeedState.ContentReady
            } else {
                current.initialFeedState
            },
            profiles = profiles,
            // 付随情報は追跡中の投稿の分を表示外でも残す。ストアから導出した値なので、
            // 追跡をやめたとき（forgetEngagementHistory）に外す。ここで捨てると再受信しても戻らない。
            quoteRepostEvents = current.quoteRepostEvents,
            quotedEvents = quotedEvents,
            repostedByPubkeys = current.repostedByPubkeys.filterKeys(isKept),

        )
    }

    /**
     * 受信した投稿を、取り込む前の状態で分類して数える。alreadyHeld が多いほど同じ投稿を取り直している。
     * hiddenReply は返信を出さないフィードで捨てる返信（返信先の付随情報だけ取る）。
     */
    private fun traceReceived(source: String, events: List<NostrEvent>) {
        var alreadyHeld = 0
        var hiddenReply = 0
        var repost = 0
        events.forEach { event ->
            when {
                event.kind == 6 -> if (receivedRepostEvents.containsKey(event.id)) alreadyHeld++ else repost++
                rawEvents[event.id] != null -> alreadyHeld++
                !includeRepliesInFeed && event.replyTargetId() != null -> hiddenReply++
            }
        }
        trace.count("$source.recv", events.size)
        trace.count("$source.alreadyHeld", alreadyHeld)
        trace.count("$source.hiddenReply", hiddenReply)
        trace.count("$source.repost", repost)
        if (source != "live") {
            trace.log {
                "$source commit received=${events.size} alreadyHeld=$alreadyHeld hiddenReply=$hiddenReply repost=$repost " +
                    "held=${rawEvents.values.size}"
            }
        }
    }

    /** 数が変わってから数秒後に累計を出す。変わらなければ出さない。 */
    private fun scheduleTraceDump() {
        if (traceDumpJob != null) return
        traceDumpJob = launch {
            delay(TRACE_DUMP_INTERVAL_MS)
            traceDumpJob = null
            trace.dump()
        }
    }

    private fun engagementLog(message: () -> String) = engagementTraceLog { "feed#$instanceKey ${message()}" }

    /** 件数を出している（追跡中の）投稿を共有ストアへ宣言する。 */
    private fun syncEngagementRetention() {
        engagementStore.retain(this, engagementFetcher.trackedBy(this).toSet())
    }

    private fun scheduleEngagementFetch(eventId: String) {
        if (!engagementFetcher.track(this, eventId)) return
        syncEngagementRetention()
        projectEngagement(listOf(eventId))
    }

    /** 投稿がフィードから完全に消えるとき、付随情報の追跡と表示も合わせて外す。 */
    private fun forgetEngagementHistory(eventId: String) {
        uncommittedHistoryEventIds.remove(eventId)
        engagementFetcher.forget(this, eventId)
        syncEngagementRetention()
        projectEngagement(listOf(eventId))
    }

    /**
     * 追跡中の投稿の付随情報を、ストアの集合と送信中の操作から作り直して表示に反映する。
     * 追跡していない投稿は表示から外す。件数を足し引きしないので、何度呼んでも同じ結果になる。
     */
    private fun projectEngagement(targetIds: Collection<String>) {
        if (targetIds.isEmpty()) return
        val relayScope = relayUrl?.let(::setOf)
        val own = ownPubkey
        val pubkeys = linkedSetOf<String>()
        updateFeedState(immediate = false) { state ->
            targetIds.distinct().fold(state) { acc, targetId ->
                if (!engagementFetcher.isTrackedBy(this, targetId)) {
                    acc.withoutEngagement(targetId)
                } else {
                    val engagement = (engagementStore.snapshot(targetId) ?: TargetEngagement()).scopedTo(relayScope)
                    (engagement.reactions.values + engagement.reposts.values + engagement.replies.values +
                        engagement.quotes.values).mapTo(pubkeys) { it.pubkey }
                    acc.withProjectedEngagement(targetId, engagement, own, pendingOperations.operations(targetId))
                }
            }
        }
        // 他の画面が先に受信したイベントはこのフィードの購読には届かないので、出す人のプロフィールをここで求める。
        pubkeys.forEach(::scheduleProfileFetch)
    }

    private fun hasRevealableEvents(): Boolean {
        val newest = rawEvents.newestRevealable(::isFiltered) ?: return false
        return newest >= (historyRevealOldestAt ?: Long.MIN_VALUE)
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

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in currentFeedState().profiles) {
            trace.count("profile.inState")
            return
        }
        ProfileRepository.getCached(pubkey)?.let { cachedProfile ->
            trace.count("profile.cacheHit")
            updateFeedState(immediate = false) { state ->
                state.copy(profiles = state.profiles + (pubkey to cachedProfile))
            }
            return
        }
        if (!requestedProfilePubkeys.add(pubkey)) {
            trace.count("profile.alreadyRequested")
            return
        }
        trace.count("profile.ensure")
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

    /**
     * 受信した付随情報をストアへ入れる。件数はストアの変化を受けて [projectEngagement] で導出する。
     * ストアは同じイベントを1件として扱うので、ここでは重複を気にしない。
     */
    private fun handleEngagementEvent(event: NostrEvent, relayUrl: String? = null) {
        if (event.kind == COMMENT_EVENT_KIND && !event.isSupportedTimelineComment()) return
        engagementStore.observe(event, relayUrl)
        scheduleProfileFetch(event.pubkey)
        if (event.kind == 1 || event.kind == COMMENT_EVENT_KIND) scheduleMentionedProfileFetch(event.content)
    }

    private fun scheduleQuoteFetch(eventIds: List<String>) {
        val missingIds = eventIds.filter { id ->
            id !in currentFeedState().quotedEvents && pendingQuoteIds.add(id)
        }
        if (missingIds.isEmpty()) return
        launch {
            val ids = subscriptionIds ?: return@launch
            trace.count("quote.req")
            trace.count("quote.idsSent", pendingQuoteIds.size)
            trace.count("quote.idsNew", missingIds.size)
            trace.log { "quote REQ ${ids.quote} ids=${pendingQuoteIds.size} new=${missingIds.size}" }
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
        private const val MAX_UNCOMMITTED_HISTORY_EVENTS = 5_000
        private const val MAX_SEEN_IDS = 2000
        private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
        private const val TIMELINE_BATCH_DELAY_MS = 150L
        private const val FEED_STATE_EMIT_DELAY_MS = 150L
        private const val REFRESH_INDICATOR_TIMEOUT_MS = 2_500L
        private const val INITIAL_FEED_SLOW_DELAY_MS = 2_500L
        private const val HISTORY_FETCH_TIMEOUT_MS = 10_000L
        private const val HISTORY_RELAY_SETTLE_DELAY_MS = 2_000L
        private const val TRACE_DUMP_INTERVAL_MS = 5_000L
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

/**
 * ストアの集合（[engagement]、リレーの範囲で絞り済み）から件数を導出し、送信中の操作を重ねて表示に反映する。
 * 送信中の操作は、集合にすでに結果が入っていれば差分を足さない（[EngagementReducer.rebase]）。
 */
internal fun FeedViewModel.UiState.withProjectedEngagement(
    eventId: String,
    engagement: TargetEngagement,
    ownPubkey: String?,
    pending: Map<EngagementSlot, PendingEngagementOperation>,
): FeedViewModel.UiState {
    val summary = summarizeEngagement(engagement, ownPubkey)
    val display = EngagementReducer.rebase(summary.toNoteEngagementState(), pending)
    fun Map<String, NostrEvent>.ordered() = values.sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
    val reposts = engagement.reposts.ordered()
    val quotes = engagement.quotes.ordered()
    return withEngagement(eventId, display).copy(
        reactionEvents = reactionEvents.putListOrRemove(eventId, engagement.reactions.ordered()),
        replyCounts = replyCounts + (eventId to summary.replyCount),
        replies = replies.putListOrRemove(eventId, engagement.replies.ordered()),
        repostEvents = repostEvents.putListOrRemove(eventId, reposts),
        quoteRepostEvents = quoteRepostEvents.putListOrRemove(eventId, quotes),
        repostPubkeys = repostPubkeys.putListOrRemove(eventId, (reposts + quotes).map { it.pubkey }.distinct()),
    )
}

/** 追跡をやめた投稿の付随情報を表示から外す。 */
private fun FeedViewModel.UiState.withoutEngagement(eventId: String): FeedViewModel.UiState = copy(
    reactionCounts = reactionCounts - eventId,
    likeReactionCounts = likeReactionCounts - eventId,
    customReactions = customReactions - eventId,
    unicodeReactions = unicodeReactions - eventId,
    reactionEvents = reactionEvents - eventId,
    likedReactions = likedReactions - eventId,
    ownEmojiReactionEventIds = ownEmojiReactionEventIds - eventId,
    replyCounts = replyCounts - eventId,
    replies = replies - eventId,
    repostCounts = repostCounts - eventId,
    repostEvents = repostEvents - eventId,
    quoteRepostEvents = quoteRepostEvents - eventId,
    repostPubkeys = repostPubkeys - eventId,
    repostedEvents = repostedEvents - eventId,
    pendingEngagementOperations = pendingEngagementOperations - eventId,
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
    val reaction: String,
    val repostTarget: String,
    val quote: String,
)
