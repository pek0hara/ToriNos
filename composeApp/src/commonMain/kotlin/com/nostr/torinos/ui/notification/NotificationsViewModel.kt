package com.nostr.torinos.ui.notification

import com.nostr.torinos.badge.badgeAwardAddress
import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.isSupportedTimelineComment
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.TargetEventLoader
import com.nostr.torinos.network.TargetLoadState
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.network.fetchFiniteEvents
import androidx.lifecycle.viewModelScope
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.ui.profile.FollowerCountStore
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class NotificationItem(
    val id: String,
    val type: NotificationType,
    val actorPubkey: String,
    val createdAt: Long?,
    val receivedAt: Long,
    val targetEventId: String?,
    val event: NostrEvent?,
    @Transient val targetReference: TargetReference = TargetReference.None,
)

@Serializable
enum class NotificationType {
    Reply,
    Repost,
    Like,
    Follow,
    BadgeAward,
    Quote,
}

data class NotificationsState(
    val items: List<NotificationItem> = emptyList(),
    val readItemIds: Set<String> = emptySet(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val targetStates: Map<String, TargetLoadState> = emptyMap(),
    val isInitialLoad: Boolean = true,
) {
    val targetEvents: Map<String, NostrEvent>
        get() = targetStates.mapNotNull { (id, state) ->
            (state as? TargetLoadState.Resolved)?.let { id to it.event }
        }.toMap()
    val hasUnread: Boolean
        get() = items.any { it.id !in readItemIds }
}

class NotificationsViewModel(
    private val ownPubkey: String,
    private val muteStore: MuteStore? = null,
) : SafeViewModel() {
    private val _state = MutableStateFlow(NotificationsState())
    val state: StateFlow<NotificationsState> = _state.asStateFlow()

    private val syncCoordinator = NotificationSyncCoordinator(
        scope = viewModelScope,
        idPrefix = "notif-$shortKey",
        onActivity = ::handleActivityEvent,
        onFollow = ::handleFollowEvent,
    )
    private val saveMutex = Mutex()
    private val targetLoader = TargetEventLoader(viewModelScope)

    private val seenItemIds = linkedSetOf<String>()
    private val pendingRelevance = PendingNotifications()
    // 引用リポストを `#q` で探すための自分の最近の投稿。端末に残し、起動時は取り直しを待たずに使う。
    private var ownRecentPosts: List<OwnPostRef> = emptyList()
    private var ownPostIds: Set<String> = emptySet()
    private var ownPostsRefreshJob: Job? = null
    private val pendingPubkeys = linkedSetOf<String>()
    private val collectorJobs = mutableListOf<Job>()
    private var profileBatchJob: Job? = null
    private var startupSyncJob: Job? = null
    private var liveSubscriptionJob: Job? = null
    private var hasStoredFollowerList = false
    // フォロー検出(kind 3)を差分取得するための前回同期時刻。未保存なら全件取得する。
    private var followSyncedAt: Long? = null
    private val knownFollowerPubkeys = linkedSetOf<String>()
    private var liveSubscriptionsStarted = false
    private var startupSyncActive = true

    private val shortKey: String
        get() = ownPubkey.take(16)

    init {
        startCollectors()
        startupSyncJob = launch {
            try {
                setOwnRecentPosts(LocalNotificationStore.loadOwnRecentPosts(ownPubkey))
                loadStoredNotifications()
                loadKnownFollowers()
                refreshOwnRecentPosts()
                syncOnce()
                ownPostsRefreshJob?.join()
                // Target fetches own their full finite deadline, not the activity EOSE + 1s.
                if (!liveSubscriptionsStarted) targetLoader.awaitIdle()
            } finally {
                startupSyncActive = false
                _state.update { it.copy(isInitialLoad = false) }
                if (!liveSubscriptionsStarted) targetLoader.stop()
            }
        }
    }

    private fun startCollectors() {
        collectorJobs += launch {
            // 自分のフォロワーを全部数えたら、その人たちを既知フォロワーに足す。
            // 足さないと、一覧に入っていない古いフォロワーが kind 3 を出し直したときに新規と誤って通知する。
            FollowerCountStore.shared.completedCounts.collect { completed ->
                if (completed.pubkey != ownPubkey || !hasStoredFollowerList) return@collect
                if (knownFollowerPubkeys.addAll(completed.followers)) saveKnownFollowers()
            }
        }
        collectorJobs += launch {
            ProfileRepository.observeChanges().collect { changedPubkeys ->
                val currentProfiles = _state.value.profiles
                val targets = pendingPubkeys + currentProfiles.keys
                val affected = if (changedPubkeys.isEmpty()) targets else changedPubkeys.intersect(targets)
                if (affected.isEmpty()) return@collect
                val profiles = currentProfiles - affected + ProfileRepository.getCached(affected)
                if (profiles != _state.value.profiles) _state.update { it.copy(profiles = profiles) }
            }
        }
        collectorJobs += launch {
            targetLoader.states.collect { targets ->
                val previous = _state.value.targetStates
                _state.update { it.copy(targetStates = targets) }
                targets.forEach { (id, state) ->
                    if (state is TargetLoadState.Resolved && previous[id] != state) {
                        scheduleProfileFetch(state.event.pubkey)
                    }
                }
                settlePendingRelevance(targets)
            }
        }
        muteStore?.let { store ->
            collectorJobs += launch {
                store.mutedPubkeys.collect { mutedPubkeys ->
                    removeMutedNotifications(mutedPubkeys)
                }
            }
        }
    }

    private suspend fun syncOnce() {
        val syncStartedAt = Clock.System.now().epochSeconds
        val result = syncCoordinator.sync(
            activityFilters(INITIAL_SYNC_LIMIT),
            followSyncSince(hasStoredFollowerList, followSyncedAt).let { since ->
                followFilter(ownPubkey, initialFollowSyncLimit(since), since)
            },
            syncStartedAt,
        )
        delay(AUXILIARY_FETCH_DRAIN_MS)
        if (result.canCommitFollowerBaseline) {
            // 基準集合を保存してから時刻を進める。失敗時は従来の位置で再取得する。
            saveKnownFollowers()
            hasStoredFollowerList = true
            LocalNotificationStore.saveFollowSyncedAt(ownPubkey, syncStartedAt)
            followSyncedAt = syncStartedAt
        }
        _state.update { it.copy(isInitialLoad = false) }
    }

    fun startLiveSubscriptions() {
        if (liveSubscriptionsStarted) return
        liveSubscriptionsStarted = true
        scheduleMissingDetails()
        refreshOwnRecentPosts()
        liveSubscriptionJob?.cancel()
        liveSubscriptionJob = launch {
            syncCoordinator.setVisible(
                true, activityFilters(LIVE_SYNC_LIMIT),
                followFilter(ownPubkey, LIVE_SYNC_LIMIT, followSyncSince(hasStoredFollowerList, followSyncedAt)),
                Clock.System.now().epochSeconds,
            )
        }
    }

    fun stopLiveSubscriptions() {
        if (!liveSubscriptionsStarted) return
        liveSubscriptionsStarted = false
        liveSubscriptionJob?.cancel()
        liveSubscriptionJob = null
        if (!startupSyncActive) cancelAuxiliaryFetches()
        liveSubscriptionJob = launch {
            syncCoordinator.setVisible(false, emptyList(), followFilter(ownPubkey, LIVE_SYNC_LIMIT, null), Clock.System.now().epochSeconds)
        }
    }

    private fun stopAllSubscriptions() {
        collectorJobs.forEach { it.cancel() }
        cancelAuxiliaryFetches()
        startupSyncJob?.cancel()
        liveSubscriptionJob?.cancel()
        // viewModelScopeはonClearedでキャンセル済みの場合もあるため終了専用scopeを使う。
        kotlinx.coroutines.CoroutineScope(NonCancellable + Dispatchers.Main).launch { syncCoordinator.close() }
    }

    private fun activityFilters(limit: Int): List<NostrFilter> = listOfNotNull(
        NostrFilter(kinds = listOf(1, 6, 16, 7), pTags = listOf(ownPubkey), limit = limit),
        NostrFilter(kinds = listOf(8), pTags = listOf(ownPubkey), limit = limit),
        NostrFilter(kinds = listOf(COMMENT_EVENT_KIND), rootKindTags = listOf("1"), pTags = listOf(ownPubkey), limit = limit),
        NostrFilter(kinds = listOf(COMMENT_EVENT_KIND), rootKindTags = listOf("1"), rootPubkeyTags = listOf(ownPubkey), limit = limit),
        quoteFilter(ownRecentPosts.map { it.id }, limit),
    )

    private fun setOwnRecentPosts(posts: List<OwnPostRef>) {
        ownRecentPosts = posts
        ownPostIds = posts.mapTo(hashSetOf()) { it.id }
    }

    /**
     * 自分の最近の投稿を取り直す。増えた分は、その引用を追加で取り、開いている購読にも足す。
     * 取れたリレーの分だけで進め、取れなかったときは保存済みの一覧を使い続ける。
     */
    private fun refreshOwnRecentPosts() {
        if (ownPostsRefreshJob?.isActive == true) return
        ownPostsRefreshJob = launch {
            val result = fetchFiniteEvents(
                SubscriptionSpec(
                    id = "notif-$shortKey-own-posts-${Clock.System.now().toEpochMilliseconds()}",
                    filters = listOf(ownRecentPostsFilter(ownPubkey)),
                    behavior = SubscriptionBehavior.Fetch(10_000),
                ),
                settleAfterEoseMillis = OWN_POSTS_SETTLE_MS,
            )
            val merged = mergeOwnRecentPosts(ownRecentPosts, result.events, ownPubkey)
            val added = merged.map { it.id }.filterNot { it in ownPostIds }
            if (added.isEmpty()) return@launch
            setOwnRecentPosts(merged)
            LocalNotificationStore.saveOwnRecentPosts(ownPubkey, merged)
            settlePendingRelevance(_state.value.targetStates)
            syncCoordinator.updateActivityFilters(activityFilters(LIVE_SYNC_LIMIT))
            quoteFilter(added, INITIAL_SYNC_LIMIT)?.let { filter ->
                syncCoordinator.fetchActivity(listOf(filter), Clock.System.now().epochSeconds)
            }
        }
    }

    private suspend fun handleActivityEvent(event: NostrEvent) {
        if (event.pubkey == ownPubkey) return
        if (event.kind == COMMENT_EVENT_KIND && !event.isSupportedTimelineComment()) return
        if (muteStore?.isMuted(event.pubkey) == true) {
            rememberSeenId(event.id)
            return
        }
        val type = when (event.kind) {
            1 -> notificationTypeForNote(event)
            COMMENT_EVENT_KIND -> NotificationType.Reply
            6, 16 -> NotificationType.Repost
            7 -> NotificationType.Like
            8 -> NotificationType.BadgeAward
            else -> return
        }
        if (type == NotificationType.BadgeAward) {
            // Anyone can award anyone, so only a correctly signed award naming this account is shown.
            if (badgeAwardAddress(event, ownPubkey) == null || !withContext(Dispatchers.Default) { isValidEvent(event) }) {
                rememberSeenId(event.id)
                return
            }
            addItem(NotificationItem(id = event.id, type = type, actorPubkey = event.pubkey, createdAt = event.createdAt,
                receivedAt = event.createdAt, targetEventId = null, event = event))
            scheduleProfileFetch(event.pubkey)
            return
        }
        val ownIds = ownPostIds
        val relevance = withContext(Dispatchers.Default) { notificationRelevance(event, ownPubkey, ownIds) }
        if (relevance == NotificationRelevance.Unrelated) {
            rememberSeenId(event.id)
            return
        }
        val resolved = withContext(Dispatchers.Default) { resolveNotificationTarget(event) }
        val targetEventId = (resolved.reference as? TargetReference.EventId)?.id
        val item = NotificationItem(
            id = event.id,
            type = type,
            actorPubkey = event.pubkey,
            createdAt = event.createdAt,
            receivedAt = event.createdAt,
            targetEventId = targetEventId,
            event = event,
            targetReference = resolved.reference,
        )
        if (relevance is NotificationRelevance.NeedsTargetAuthor) {
            if (!rememberSeenId(event.id)) return
            holdUntilTargetAuthorKnown(item, relevance.eventIds)
            return
        }
        addItem(item)
        scheduleProfileFetch(event.pubkey)
        resolved.embedded?.let { targetLoader.seedValidated(it) }
        if (targetEventId != null && _state.value.items.any { it.id == event.id }) {
            scheduleTargetFetch(targetEventId)
        }
        retainTargets()
    }

    private suspend fun handleFollowEvent(event: NostrEvent) {
        if (event.pubkey == ownPubkey) return
        if (!event.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == ownPubkey }) return
        if (!knownFollowerPubkeys.add(event.pubkey)) return
        if (!hasStoredFollowerList) return
        saveKnownFollowers()
        // ミュート中でもフォロワーには違いないので数には足す。
        FollowerCountStore.shared.recordNewFollower(ownPubkey)
        if (muteStore?.isMuted(event.pubkey) == true) {
            rememberSeenId(event.id)
            return
        }

        addItem(
            NotificationItem(
                id = event.id,
                type = NotificationType.Follow,
                actorPubkey = event.pubkey,
                createdAt = null,
                receivedAt = Clock.System.now().epochSeconds,
                targetEventId = null,
                event = null,
            ),
        )
        scheduleProfileFetch(event.pubkey)
    }

    private fun addItem(item: NotificationItem) {
        if (!rememberSeenId(item.id)) return
        insertItem(item)
    }

    private fun insertItem(item: NotificationItem) {
        _state.update { state ->
            state.copy(
                items = (state.items.filterNot { it.id == item.id } + item)
                    .sortedByDescending { it.receivedAt }.take(MAX_ITEMS),
            )
        }
        saveStoredNotifications()
    }

    private fun holdUntilTargetAuthorKnown(item: NotificationItem, eventIds: Set<String>) {
        pendingRelevance.hold(item, eventIds)
        eventIds.forEach(::scheduleTargetFetch)
        retainTargets()
        settlePendingRelevance(_state.value.targetStates)
    }

    private fun settlePendingRelevance(states: Map<String, TargetLoadState>) {
        val settled = pendingRelevance.settle(states, ownPubkey, ownPostIds)
        settled.shown.forEach { item ->
            insertItem(item)
            scheduleDetailsForItem(item)
        }
        if (settled.decided) {
            retainTargets()
            saveStoredNotifications()
        }
    }

    fun markAllRead() {
        _state.update { state ->
            state.copy(readItemIds = state.readItemIds + state.items.map { it.id })
        }
        saveStoredNotifications()
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (!canFetchAuxiliaryDetails()) return
        if (pubkey in _state.value.profiles || !pendingPubkeys.add(pubkey)) return
        ProfileRepository.getCached(pubkey)?.let { profile ->
            _state.update { it.copy(profiles = it.profiles + (pubkey to profile)) }
        }
        profileBatchJob?.cancel()
        profileBatchJob = launch {
            delay(400)
            if (!canFetchAuxiliaryDetails()) {
                pendingPubkeys.clear()
                return@launch
            }
            val pubkeys = pendingPubkeys.toSet()
            ProfileRepository.ensureProfiles(
                pubkeys,
                ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS),
            )
        }
    }

    private fun scheduleTargetFetch(eventId: String) {
        if (!canFetchAuxiliaryDetails()) return
        targetLoader.request(eventId)
    }

    fun retryTarget(eventId: String) {
        if (liveSubscriptionsStarted && _state.value.items.any { it.targetEventId == eventId }) {
            targetLoader.request(eventId, retry = true)
        }
    }

    private fun retainTargets() {
        targetLoader.retain(
            _state.value.items.mapNotNull { it.targetEventId }.toSet() + pendingRelevance.targetIds,
        )
    }

    private fun rememberSeenId(eventId: String): Boolean {
        if (!seenItemIds.add(eventId)) return false
        while (seenItemIds.size > MAX_SEEN_IDS) {
            seenItemIds.remove(seenItemIds.first())
        }
        return true
    }

    private suspend fun loadKnownFollowers() {
        val decoded = LocalNotificationStore.loadKnownFollowers(ownPubkey)
        hasStoredFollowerList = decoded != null
        followSyncedAt = LocalNotificationStore.loadFollowSyncedAt(ownPubkey)
        knownFollowerPubkeys.clear()
        decoded?.let { pubkeys ->
            knownFollowerPubkeys += pubkeys.filter { it.isNotBlank() }
        }
    }

    private suspend fun saveKnownFollowers() {
        LocalNotificationStore.saveKnownFollowers(ownPubkey, knownFollowerPubkeys.toList())
    }

    private suspend fun loadStoredNotifications() {
        val stored = LocalNotificationStore.load(ownPubkey)
        val normalized = withContext(Dispatchers.Default) {
            stored.items.map { item ->
                val resolved = resolveNotificationTarget(item.event, item.targetEventId)
                item.copy(
                    // 以前の版は引用リポストも返信として保存していた。
                    type = item.event?.takeIf { item.type == NotificationType.Reply && it.kind == 1 }
                        ?.let(::notificationTypeForNote) ?: item.type,
                    targetEventId = (resolved.reference as? TargetReference.EventId)?.id,
                    targetReference = resolved.reference,
                ) to resolved.embedded
            }
        }
        val unmuted = normalized.map { it.first }.filterNotMutedActors(muteStore?.mutedPubkeys?.value.orEmpty())
        val ownIds = ownPostIds
        val triage = withContext(Dispatchers.Default) { triageStoredNotifications(unmuted, ownPubkey, ownIds) }
        val items = triage.kept
        seenItemIds += stored.items.map { it.id }
        _state.update {
            it.copy(
                items = (it.items + items).distinctBy { item -> item.id }
                    .sortedByDescending { item -> item.receivedAt }.take(MAX_ITEMS),
                readItemIds = it.readItemIds + stored.readItemIds,
            )
        }
        normalized.filter { pair -> items.any { it.id == pair.first.id } }
            .mapNotNull { it.second }.forEach(targetLoader::seedValidated)
        _state.value.items.forEach { item ->
            scheduleDetailsForItem(item)
        }
        retainTargets()
        triage.held.forEach { (item, eventIds) -> holdUntilTargetAuthorKnown(item, eventIds) }
        if (items.size != stored.items.size) {
            LocalNotificationStore.save(
                ownPubkey,
                stored.copy(items = items + pendingRelevance.items),
            )
        }
    }

    private fun removeMutedNotifications(mutedPubkeys: Set<String>) {
        pendingRelevance.removeMutedActors(mutedPubkeys)
        val current = _state.value
        val items = current.items.filterNotMutedActors(mutedPubkeys)
        if (items.size == current.items.size) return
        _state.update { it.copy(items = items) }
        retainTargets()
        saveStoredNotifications()
    }

    private fun scheduleMissingDetails() {
        _state.value.items.forEach { item ->
            scheduleDetailsForItem(item)
        }
        pendingRelevance.targetIds.forEach(::scheduleTargetFetch)
    }

    private fun scheduleDetailsForItem(item: NotificationItem) {
        scheduleProfileFetch(item.actorPubkey)
        item.targetEventId?.let { scheduleTargetFetch(it) }
        item.targetEventId?.let { _state.value.targetEvents[it] }?.let { scheduleProfileFetch(it.pubkey) }
    }

    private fun saveStoredNotifications() {
        val snapshot = _state.value
        // 確認待ちも残しておき、終了しても次回の起動で振り分け直す。
        val stored = StoredNotifications(
            items = (snapshot.items + pendingRelevance.items)
                .sortedByDescending { it.receivedAt }.take(MAX_ITEMS),
            readItemIds = snapshot.readItemIds,
        )
        launch {
            saveMutex.withLock { LocalNotificationStore.save(ownPubkey, stored) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAllSubscriptions()
    }

    private fun canFetchAuxiliaryDetails(): Boolean =
        liveSubscriptionsStarted || startupSyncActive

    private fun cancelAuxiliaryFetches() {
        profileBatchJob?.cancel()
        profileBatchJob = null
        targetLoader.stop()
        pendingPubkeys.clear()
    }


    companion object {
        private const val MAX_ITEMS = 100
        private const val MAX_SEEN_IDS = 1000
        private const val INITIAL_SYNC_LIMIT = 100
        private const val LIVE_SYNC_LIMIT = 100
        private const val INITIAL_SYNC_TIMEOUT_MS = 10_000L
        private const val AUXILIARY_FETCH_DRAIN_MS = 1_000L
        private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
        private const val OWN_POSTS_SETTLE_MS = 1_000L
    }
}

internal fun List<NotificationItem>.filterNotMutedActors(
    mutedPubkeys: Set<String>,
): List<NotificationItem> {
    if (mutedPubkeys.isEmpty()) return this
    val normalizedMutedPubkeys = mutedPubkeys.mapTo(hashSetOf()) { it.trim().lowercase() }
    return filterNot { it.actorPubkey.trim().lowercase() in normalizedMutedPubkeys }
}

/** 新しい順に最大 [limit] 件のフォロワーの contact list(kind 3)。フォロー1件が巨大なので、可能なら [since] で絞る。 */
internal fun followFilter(ownPubkey: String, limit: Int, since: Long?): NostrFilter =
    NostrFilter(kinds = listOf(3), pTags = listOf(ownPubkey), since = since, limit = limit)

/**
 * 既知フォロワーの一覧と前回同期時刻が揃っているときだけ、その時刻(余裕つき)以降に絞る。
 * 一覧が未保存なら基準を作るため、従来どおり全件取得する。
 */
internal fun followSyncSince(hasStoredFollowerList: Boolean, lastSyncedAt: Long?): Long? {
    if (!hasStoredFollowerList || lastSyncedAt == null) return null
    return (lastSyncedAt - FOLLOW_SYNC_OVERLAP_SECONDS).coerceAtLeast(0L)
}

// リレーへの伝搬遅れやクライアントの時計ずれを吸収する。既知フォロワーは重複排除される。
/**
 * 起動時のフォロー取得件数。既知フォロワー一覧をまだ持たない（since なし）ときは基準を作るので多めに取り、
 * 差分のときは少なくてよい。
 */
internal fun initialFollowSyncLimit(since: Long?): Int =
    if (since == null) FOLLOW_BASELINE_LIMIT else FOLLOW_DELTA_LIMIT

internal const val FOLLOW_BASELINE_LIMIT = 500
internal const val FOLLOW_DELTA_LIMIT = 100

internal const val FOLLOW_SYNC_OVERLAP_SECONDS = 24 * 60 * 60L
