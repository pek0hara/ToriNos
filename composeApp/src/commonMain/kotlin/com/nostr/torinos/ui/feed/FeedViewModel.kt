package com.nostr.torinos.ui.feed

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.crypto.loadPublicKey
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.NgWordStore
import com.nostr.torinos.network.NostrRepository
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Job
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FeedViewModel(
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
        val replyCounts: Map<String, Int> = emptyMap(),
        val repostCounts: Map<String, Int> = emptyMap(),
        val quotedEvents: Map<String, NostrEvent> = emptyMap(),
        val repostedByPubkeys: Map<String, String> = emptyMap(),
        /** postId → 自分のリアクションイベントID */
        val likedReactions: Map<String, String> = emptyMap(),
        /** postId → 自分のリポストイベントID */
        val repostedEvents: Map<String, String> = emptyMap(),
        val canLoadMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        /** true = 初回 EOSE 待ち（ローディングスピナー表示） */
        val isInitialLoad: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val instanceKey = nextInstanceKey()
    private val shortKey = authorPubkey?.take(16) ?: authorPubkeys?.hashCode()?.toString() ?: "global"
    private val subscriptionJobs = mutableListOf<Job>()
    private var subscriptionIds: SubscriptionIds? = null
    private var subscriptionGeneration = 0
    private val pendingPubkeys = mutableSetOf<String>()
    private var profileBatchJob: Job? = null
    private var historyPageTimeoutJob: Job? = null
    private val watchedEventIds = linkedSetOf<String>()
    private var engagementBatchJob: Job? = null
    private val seenReactionIds = linkedSetOf<String>()
    private val seenReplyIds = linkedSetOf<String>()
    private val seenRepostIds = linkedSetOf<String>()
    private val seenEventIds = linkedSetOf<String>()
    private val rawEvents = linkedMapOf<String, NostrEvent>()
    private val canonicalEvents = linkedMapOf<String, NostrEvent>()
    private val eventSortTimes = mutableMapOf<String, Long>()
    private val pendingQuoteIds = linkedSetOf<String>()
    private val pendingRepostTargets = mutableMapOf<String, PendingRepostTarget>()
    private var subscriptionsStarted = false
    private var ownPubkey: String? = null

    private var oldestCreatedAt: Long? = null
    private var loadingMore = false
    private var isGapFill = false
    private var lastHistoryBatchReceivedCount = 0
    private var lastHistoryBatchUniqueCount = 0
    private var receivedEoseCount = 0
    private var expectedEoseCount = 1
    private var initialHistoryRequested = false
    private val shouldRefreshLiveSubscription: Boolean
        get() = authorPubkey == null && authorPubkeys != null

    init {
        if (isWriteSupported) launch { ownPubkey = loadPublicKey() }
        if (autoStart) startSubscriptions()
    }

    fun injectProfile(pubkey: String, profile: com.nostr.torinos.model.NostrProfile) {
        if (_state.value.profiles.containsKey(pubkey)) return
        _state.value = _state.value.copy(profiles = _state.value.profiles + (pubkey to profile))
    }

    fun deleteEvent(eventId: String) {
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: return@launch
            runCatching {
                val deletion = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = "",
                    kind = 5,
                    tags = listOf(listOf("e", eventId)),
                )
                NostrRepository.publish(deletion)
            }
            val cur = _state.value
            _state.value = cur.copy(events = cur.events.filter { it.id != eventId })
            seenEventIds.remove(eventId)
            rawEvents.remove(eventId)
            canonicalEvents.remove(eventId)
            eventSortTimes.remove(eventId)
        }
    }

    fun react(eventId: String, eventPubkey: String) {
        val cur = _state.value
        if (cur.likedReactions.containsKey(eventId)) return
        // 楽観的UI更新（reactionEventIdは署名後に確定）
        _state.value = cur.copy(
            likedReactions = cur.likedReactions + (eventId to ""),
            reactionCounts = cur.reactionCounts + (eventId to (cur.reactionCounts[eventId] ?: 0) + 1),
        )
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: return@launch
            runCatching {
                val reaction = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = "+",
                    kind = 7,
                    tags = listOf(listOf("e", eventId), listOf("p", eventPubkey)),
                )
                rememberSeenId(seenReactionIds, reaction.id)
                NostrRepository.publish(reaction)
                // 署名済みイベントIDを保存（後でキャンセルに使用）
                _state.value = _state.value.copy(
                    likedReactions = _state.value.likedReactions + (eventId to reaction.id),
                )
            }
        }
    }

    fun unreact(eventId: String) {
        val cur = _state.value
        val reactionEventId = cur.likedReactions[eventId] ?: return
        // 楽観的UI更新
        _state.value = cur.copy(
            likedReactions = cur.likedReactions - eventId,
            reactionCounts = cur.reactionCounts + (eventId to maxOf(0, (cur.reactionCounts[eventId] ?: 0) - 1)),
        )
        if (reactionEventId.isEmpty()) return
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: return@launch
            runCatching {
                val deletion = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = "",
                    kind = 5,
                    tags = listOf(listOf("e", reactionEventId)),
                )
                NostrRepository.publish(deletion)
            }
        }
    }

    fun repost(event: NostrEvent) {
        val cur = _state.value
        if (cur.repostedEvents.containsKey(event.id)) return
        val eventToRepost = canonicalEvents[event.id] ?: event
        _state.value = cur.copy(
            repostedEvents = cur.repostedEvents + (event.id to ""),
            repostCounts = cur.repostCounts + (event.id to (cur.repostCounts[event.id] ?: 0) + 1),
        )
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: return@launch
            runCatching {
                val repostEvent = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = Json.encodeToString(NostrEvent.serializer(), eventToRepost),
                    kind = 6,
                    tags = listOf(listOf("e", eventToRepost.id), listOf("p", eventToRepost.pubkey)),
                )
                rememberSeenId(seenRepostIds, repostEvent.id)
                NostrRepository.publish(repostEvent)
                _state.value = _state.value.copy(
                    repostedEvents = _state.value.repostedEvents + (event.id to repostEvent.id),
                )
            }
        }
    }

    fun unrepost(eventId: String) {
        val cur = _state.value
        val repostEventId = cur.repostedEvents[eventId] ?: return
        _state.value = cur.copy(
            repostedEvents = cur.repostedEvents - eventId,
            repostCounts = cur.repostCounts + (eventId to maxOf(0, (cur.repostCounts[eventId] ?: 0) - 1)),
        )
        if (repostEventId.isEmpty()) return
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: return@launch
            runCatching {
                val deletion = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = "",
                    kind = 5,
                    tags = listOf(listOf("e", repostEventId)),
                )
                NostrRepository.publish(deletion)
            }
        }
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
        val ids = newSubscriptionIds()
        subscriptionIds = ids

        subscriptionJobs += launch {
            val current = _state.value
            if (current.isInitialLoad && current.events.isEmpty() && !initialHistoryRequested) {
                // 初回のみ履歴ページを取得
                requestHistoryPage(until = null)
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

        // フィードイベント収集（ライブ：feedSubId）
        subscriptionJobs += launch {
            NostrRepository.events(ids.feed).collect { event ->
                appendFeedEvent(event)
            }
        }

        // 過去ページイベント収集（historySubId）
        subscriptionJobs += launch {
            NostrRepository.events(ids.history).collect { event ->
                lastHistoryBatchReceivedCount++
                lastHistoryBatchUniqueCount += appendFeedEvent(event)
            }
        }

        // EOSE でローディング解除
        subscriptionJobs += launch {
            NostrRepository.eose(ids.history).collect {
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
                _state.value = current.copy(
                    isInitialLoad = false,
                    canLoadMore = false,
                    isLoadingMore = false,
                )
                NostrRepository.close(ids.history)
            }
        }

        // リレーが feedSubId を CLOSED したら再購読（接続維持中でも切られることがある）
        subscriptionJobs += launch {
            NostrRepository.closed(ids.feed).collect {
                if (!subscriptionsStarted) return@collect
                subscribeLiveFeed(since = liveSince())
            }
        }

        // エンゲージメント購読が CLOSED されたら再購読
        for (subId in listOf(ids.reaction, ids.reply, ids.repost)) {
            subscriptionJobs += launch {
                NostrRepository.closed(subId).collect {
                    if (!subscriptionsStarted) return@collect
                    resubscribeEngagement()
                }
            }
        }

        // リレーが CLOSED を返さずに購読だけ止めるケースに備え、フォロータブは定期的に REQ を再送する。
        if (shouldRefreshLiveSubscription) {
            subscriptionJobs += launch {
                while (subscriptionsStarted) {
                    delay(LIVE_SUBSCRIPTION_REFRESH_INTERVAL_MS)
                    if (subscriptionsStarted) {
                        subscribeLiveFeed(since = liveSince())
                    }
                }
            }
        }

        // プロフィール受信（kind:0）
        subscriptionJobs += launch {
            NostrRepository.events(ids.profile).collect { event ->
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
            NostrRepository.events(ids.reaction).collect { event ->
                if (event.kind != 7) return@collect
                if (!rememberSeenId(seenReactionIds, event.id)) return@collect
                val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                    ?: return@collect
                val cur = _state.value
                val isOwn = ownPubkey != null && event.pubkey == ownPubkey
                _state.value = cur.copy(
                    reactionCounts = cur.reactionCounts +
                        (targetId to (cur.reactionCounts[targetId] ?: 0) + 1),
                    likedReactions = if (isOwn && !cur.likedReactions.containsKey(targetId))
                        cur.likedReactions + (targetId to event.id)
                    else cur.likedReactions,
                )
            }
        }

        // リプライ受信（kind:1 with e-tag）
        subscriptionJobs += launch {
            NostrRepository.events(ids.reply).collect { event ->
                if (event.kind != 1) return@collect
                if (!rememberSeenId(seenReplyIds, event.id)) return@collect
                val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                    ?: return@collect
                val cur = _state.value
                _state.value = cur.copy(
                    replyCounts = cur.replyCounts +
                        (targetId to (cur.replyCounts[targetId] ?: 0) + 1),
                )
            }
        }

        // リポスト受信（kind:6）
        subscriptionJobs += launch {
            NostrRepository.events(ids.repost).collect { event ->
                if (event.kind != 6) return@collect
                if (!rememberSeenId(seenRepostIds, event.id)) return@collect
                val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                    ?: return@collect
                val cur = _state.value
                val isOwn = ownPubkey != null && event.pubkey == ownPubkey
                _state.value = cur.copy(
                    repostCounts = cur.repostCounts +
                        (targetId to (cur.repostCounts[targetId] ?: 0) + 1),
                    repostedEvents = if (isOwn && !cur.repostedEvents.containsKey(targetId))
                        cur.repostedEvents + (targetId to event.id)
                    else cur.repostedEvents,
                )
            }
        }

        // 引用先イベント受信（nostr:note/nevent または q タグ）
        subscriptionJobs += launch {
            NostrRepository.events(ids.quote).collect { event ->
                if (event.kind != 1) return@collect
                val cur = _state.value
                if (cur.quotedEvents.containsKey(event.id)) return@collect
                pendingQuoteIds.remove(event.id)
                _state.value = cur.copy(quotedEvents = cur.quotedEvents + (event.id to event))
                scheduleProfileFetch(event.pubkey)
            }
        }

        // ミュート・NGワード変更時にフィルタ済みリストを再構築
        if (filterMutedUsers) {
            subscriptionJobs += launch {
                MuteStore.mutedPubkeys.collect { rebuildFilteredEvents() }
            }
        }
        subscriptionJobs += launch {
            NgWordStore.ngWords.collect { rebuildFilteredEvents() }
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
    }

    fun stopSubscriptions() {
        if (!subscriptionsStarted) return
        subscriptionsStarted = false
        val ids = subscriptionIds
        subscriptionIds = null
        subscriptionJobs.forEach { it.cancel() }
        subscriptionJobs.clear()
        profileBatchJob?.cancel()
        engagementBatchJob?.cancel()
        historyPageTimeoutJob?.cancel()
        historyPageTimeoutJob = null
        if (loadingMore) {
            loadingMore = false
            val current = _state.value
            _state.value = current.copy(
                isInitialLoad = current.isInitialLoad && current.events.isEmpty(),
                canLoadMore = current.canLoadMore || current.events.isNotEmpty(),
                isLoadingMore = false,
            )
        }
        if (_state.value.isInitialLoad && _state.value.events.isEmpty()) {
            initialHistoryRequested = false
        }
        ids?.let {
            NostrRepository.close(it.feed)
            NostrRepository.close(it.history)
            NostrRepository.close(it.profile)
            NostrRepository.close(it.reaction)
            NostrRepository.close(it.reply)
            NostrRepository.close(it.repost)
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
            _state.value = _state.value.copy(
                isInitialLoad = false,
                canLoadMore = false,
                isLoadingMore = false,
            )
            return
        }

        NostrRepository.closeSuspending(ids.history)

        isGapFill = false
        loadingMore = true
        lastHistoryBatchReceivedCount = 0
        lastHistoryBatchUniqueCount = 0
        receivedEoseCount = 0
        expectedEoseCount = if (relayUrl != null) 1 else NostrRepository.relayCount.coerceAtLeast(1)
        _state.value = _state.value.copy(canLoadMore = false, isLoadingMore = true)
        scheduleHistoryPageTimeout()

        // 初回のみライブ購読も開始（since=現在時刻でライブイベントのみ）
        if (until == null) {
            initialHistoryRequested = true
            subscribeLiveFeed(since = Clock.System.now().epochSeconds)
        }
        NostrRepository.subscribe(
            ids.history,
            NostrFilter(
                kinds = feedKinds(),
                authors = authorPubkeys,
                tTags = hashtag?.let { listOf(it) },
                until = until,
                limit = FEED_PAGE_SIZE,
            ),
            relayUrl = relayUrl,
        )
    }

    private suspend fun requestGapFill(since: Long, until: Long) {
        val ids = subscriptionIds ?: return
        if (authorPubkeys?.isEmpty() == true) return
        NostrRepository.closeSuspending(ids.history)
        isGapFill = true
        loadingMore = true
        lastHistoryBatchReceivedCount = 0
        lastHistoryBatchUniqueCount = 0
        receivedEoseCount = 0
        expectedEoseCount = if (relayUrl != null) 1 else NostrRepository.relayCount.coerceAtLeast(1)
        scheduleHistoryPageTimeout()
        NostrRepository.subscribe(
            ids.history,
            NostrFilter(
                kinds = feedKinds(),
                authors = authorPubkeys,
                tTags = hashtag?.let { listOf(it) },
                since = since,
                until = until,
                limit = FEED_PAGE_SIZE,
            ),
            relayUrl = relayUrl,
        )
    }

    private fun onHistoryPageCompleted() {
        if (!loadingMore) return
        loadingMore = false
        historyPageTimeoutJob?.cancel()
        historyPageTimeoutJob = null
        // リプライ等がフィルタされても受信件数が上限に達していれば次ページがある
        val hasMore = lastHistoryBatchReceivedCount >= FEED_PAGE_SIZE
        val cur = _state.value
        // ギャップ補完は期間が限定されるため件数で過去ページの有無を判断できない
        _state.value = cur.copy(
            canLoadMore = if (isGapFill) cur.canLoadMore else hasMore,
            isInitialLoad = false,
            isLoadingMore = false,
        )
        subscriptionIds?.let { ids ->
            if (isGapFill || !hasMore) NostrRepository.close(ids.history)
        }
    }

    private fun scheduleHistoryPageTimeout() {
        historyPageTimeoutJob?.cancel()
        historyPageTimeoutJob = launch {
            delay(10_000)
            if (!loadingMore) return@launch

            loadingMore = false
            val hasMore = lastHistoryBatchReceivedCount >= FEED_PAGE_SIZE
            val current = _state.value
            _state.value = current.copy(
                isInitialLoad = false,
                canLoadMore = if (isGapFill) current.canLoadMore else hasMore,
                isLoadingMore = false,
            )
            subscriptionIds?.let { ids ->
                if (isGapFill || !hasMore) NostrRepository.close(ids.history)
            }
        }
    }

    private suspend fun subscribeLiveFeed(since: Long) {
        val ids = subscriptionIds ?: return
        NostrRepository.subscribe(
            ids.feed,
            NostrFilter(
                kinds = feedKinds(),
                authors = authorPubkeys,
                tTags = hashtag?.let { listOf(it) },
                since = since,
            ),
            relayUrl = relayUrl,
        )
    }

    private fun liveSince(): Long {
        val latestEventAt = eventSortTimes.values.maxOrNull()
        val recentWindowStart = Clock.System.now().epochSeconds - LIVE_SUBSCRIPTION_SINCE_OVERLAP_SECONDS
        return maxOf(latestEventAt ?: recentWindowStart, recentWindowStart)
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
        val cur = _state.value
        if (cur.events.any { it.id == event.id }) return 0
        val updated = sortTimelineEvents(cur.events + event)
        _state.value = cur.copy(events = updated)
        val quoteIds = quotedEventIds(event)
        scheduleQuoteFetch(quoteIds)
        event.replyTargetId()?.takeIf { it !in quoteIds }?.let { scheduleQuoteFetch(listOf(it)) }
        return 1
    }

    private fun isFiltered(event: NostrEvent): Boolean {
        if (filterMutedUsers && MuteStore.isMuted(event.pubkey)) return true
        return NgWordStore.matches(event.content)
    }

    private fun rebuildFilteredEvents() {
        val filtered = rawEvents.values
            .filter { !isFiltered(it) }
            .let(::sortTimelineEvents)
        _state.value = _state.value.copy(events = filtered)
    }

    private fun appendRepostedEvent(repost: NostrEvent): Int {
        if (!includeRepostsInFeed || !rememberSeenId(seenRepostIds, repost.id)) return 0
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
                )
            }
        }
        return 0
    }

    private fun updateRepostState(repost: NostrEvent, targetId: String?) {
        if (targetId == null) return
        val cur = _state.value
        val isOwn = ownPubkey != null && repost.pubkey == ownPubkey
        _state.value = cur.copy(
            repostCounts = cur.repostCounts +
                (targetId to (cur.repostCounts[targetId] ?: 0) + 1),
            repostedEvents = if (isOwn && !cur.repostedEvents.containsKey(targetId))
                cur.repostedEvents + (targetId to repost.id)
            else cur.repostedEvents,
        )
    }

    private fun markRepostedBy(eventId: String, reposterPubkey: String) {
        val cur = _state.value
        _state.value = cur.copy(
            repostedByPubkeys = cur.repostedByPubkeys + (eventId to reposterPubkey),
        )
    }

    private fun updateTimelineSortTime(eventId: String, timelineCreatedAt: Long) {
        val currentSortTime = eventSortTimes[eventId]
        if (currentSortTime != null && timelineCreatedAt <= currentSortTime) return
        eventSortTimes[eventId] = timelineCreatedAt
        val cur = _state.value
        if (cur.events.none { it.id == eventId }) return
        _state.value = cur.copy(events = sortTimelineEvents(cur.events))
    }

    private fun sortTimelineEvents(events: List<NostrEvent>): List<NostrEvent> =
        events.sortedByDescending { eventSortTimes[it.id] ?: it.createdAt }

    private fun rememberSeenId(seenIds: LinkedHashSet<String>, eventId: String): Boolean {
        if (!seenIds.add(eventId)) return false
        while (seenIds.size > MAX_SEEN_IDS) seenIds.remove(seenIds.first())
        return true
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
            val ids = subscriptionIds ?: return@launch
            NostrRepository.subscribe(
                ids.profile,
                NostrFilter(kinds = listOf(0), authors = authors),
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
        NostrRepository.subscribe(subIds.reaction, NostrFilter(kinds = listOf(7), eTags = ids))
        NostrRepository.subscribe(subIds.reply,    NostrFilter(kinds = listOf(1), eTags = ids))
        NostrRepository.subscribe(subIds.repost,   NostrFilter(kinds = listOf(6), eTags = ids))
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
            val subIds = subscriptionIds ?: return@launch
            NostrRepository.subscribe(subIds.reaction, NostrFilter(kinds = listOf(7), eTags = ids))
            NostrRepository.subscribe(subIds.reply, NostrFilter(kinds = listOf(1), eTags = ids))
            NostrRepository.subscribe(subIds.repost, NostrFilter(kinds = listOf(6), eTags = ids))
        }
    }

    private fun scheduleQuoteFetch(eventIds: List<String>) {
        val missingIds = eventIds.filter { id ->
            id !in _state.value.quotedEvents && pendingQuoteIds.add(id)
        }
        if (missingIds.isEmpty()) return
        launch {
            val ids = subscriptionIds ?: return@launch
            NostrRepository.subscribe(
                ids.quote,
                NostrFilter(ids = pendingQuoteIds.toList(), kinds = listOf(1)),
            )
        }
    }

    private fun newSubscriptionIds(): SubscriptionIds {
        subscriptionGeneration++
        val suffix = "$shortKey-$instanceKey-$subscriptionGeneration"
        return SubscriptionIds(
            feed = "feed-$suffix",
            history = "hist-$suffix",
            profile = "prof-$suffix",
            reaction = "reac-$suffix",
            reply = "repl-$suffix",
            repost = "repo-$suffix",
            repostTarget = "rpt-$suffix",
            quote = "quot-$suffix",
        )
    }

    companion object {
        private const val FEED_PAGE_SIZE = 30
        private const val MAX_TRACKED_ENGAGEMENT_EVENTS = 100
        private const val MAX_SEEN_IDS = 2000
        private const val LIVE_SUBSCRIPTION_REFRESH_INTERVAL_MS = 60_000L
        private const val LIVE_SUBSCRIPTION_SINCE_OVERLAP_SECONDS = 300L
        private val nextInstanceKeyValue = kotlin.concurrent.AtomicInt(0)

        private fun nextInstanceKey(): Int = nextInstanceKeyValue.incrementAndGet()
    }
}

private data class PendingRepostTarget(
    val repostedAt: Long,
    val reposterPubkey: String,
)

private data class SubscriptionIds(
    val feed: String,
    val history: String,
    val profile: String,
    val reaction: String,
    val reply: String,
    val repost: String,
    val repostTarget: String,
    val quote: String,
)
