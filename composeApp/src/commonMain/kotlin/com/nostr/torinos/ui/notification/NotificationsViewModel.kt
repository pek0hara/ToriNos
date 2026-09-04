package com.nostr.torinos.ui.notification

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.TargetEventLoader
import com.nostr.torinos.network.TargetLoadState
import androidx.lifecycle.viewModelScope
import com.nostr.torinos.ui.SafeViewModel
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
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

    private val activitySubId = "notif-act-$shortKey"
    private val followsSubId = "notif-follow-$shortKey"
    private val targetLoader = TargetEventLoader(viewModelScope)

    private val seenItemIds = linkedSetOf<String>()
    private val pendingPubkeys = linkedSetOf<String>()
    private val collectorJobs = mutableListOf<Job>()
    private var profileBatchJob: Job? = null
    private var startupSyncJob: Job? = null
    private var liveSubscriptionJob: Job? = null
    private var hasStoredFollowerList = false
    private val knownFollowerPubkeys = linkedSetOf<String>()
    private var liveSubscriptionsStarted = false
    private var startupSyncActive = true

    private val shortKey: String
        get() = ownPubkey.take(16)

    init {
        startCollectors()
        startupSyncJob = launch {
            try {
                loadStoredNotifications()
                loadKnownFollowers()
                syncOnce()
                // Target fetches own their full finite deadline, not the activity EOSE + 1s.
                if (!liveSubscriptionsStarted) targetLoader.awaitIdle()
            } finally {
                startupSyncActive = false
                if (!liveSubscriptionsStarted) targetLoader.stop()
            }
        }
    }

    private fun startCollectors() {
        collectorJobs += launch {
            NostrRepository.events(activitySubId).collect { event ->
                handleActivityEvent(event)
            }
        }
        collectorJobs += launch {
            NostrRepository.events(followsSubId).collect { event ->
                handleFollowEvent(event)
            }
        }
        collectorJobs += launch {
            ProfileRepository.observeAll().collect { cachedProfiles ->
                val profiles = cachedProfiles.filterKeys { it in pendingPubkeys || it in _state.value.profiles }
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
        try {
            withTimeoutOrNull(INITIAL_SYNC_TIMEOUT_MS) {
                val activityEose = async { NostrRepository.eose(activitySubId).first() }
                val followsEose = async { NostrRepository.eose(followsSubId).first() }
                subscribeNotificationFeeds(limit = INITIAL_SYNC_LIMIT)
                activityEose.await()
                followsEose.await()
            }
            // Activity events schedule profile/target fetches with a short debounce.
            delay(AUXILIARY_FETCH_DRAIN_MS)
            if (!hasStoredFollowerList) {
                hasStoredFollowerList = true
                saveKnownFollowers()
            }
            _state.update { it.copy(isInitialLoad = false) }
        } finally {
            if (!liveSubscriptionsStarted) {
                closeSubscriptions()
            }
        }
    }

    fun startLiveSubscriptions() {
        if (liveSubscriptionsStarted) return
        liveSubscriptionsStarted = true
        scheduleMissingDetails()
        liveSubscriptionJob?.cancel()
        liveSubscriptionJob = launch {
            subscribeNotificationFeeds(limit = LIVE_SYNC_LIMIT)
        }
    }

    fun stopLiveSubscriptions() {
        if (!liveSubscriptionsStarted) return
        liveSubscriptionsStarted = false
        liveSubscriptionJob?.cancel()
        liveSubscriptionJob = null
        if (!startupSyncActive) cancelAuxiliaryFetches()
        closeSubscriptions()
    }

    private fun stopAllSubscriptions() {
        collectorJobs.forEach { it.cancel() }
        cancelAuxiliaryFetches()
        startupSyncJob?.cancel()
        liveSubscriptionJob?.cancel()
        closeSubscriptions()
    }

    private suspend fun subscribeNotificationFeeds(limit: Int) {
        NostrRepository.subscribe(
            activitySubId,
            NostrFilter(kinds = listOf(1, 6, 7), pTags = listOf(ownPubkey), limit = limit),
        )
        NostrRepository.subscribe(
            followsSubId,
            NostrFilter(kinds = listOf(3), pTags = listOf(ownPubkey), limit = limit),
        )
    }

    private suspend fun handleActivityEvent(event: NostrEvent) {
        if (event.pubkey == ownPubkey) return
        if (muteStore?.isMuted(event.pubkey) == true) {
            rememberSeenId(event.id)
            return
        }
        val type = when (event.kind) {
            1 -> NotificationType.Reply
            6 -> NotificationType.Repost
            7 -> NotificationType.Like
            else -> return
        }
        val resolved = withContext(Dispatchers.Default) { resolveNotificationTarget(event) }
        val targetEventId = (resolved.reference as? TargetReference.EventId)?.id
        addItem(
            NotificationItem(
                id = event.id,
                type = type,
                actorPubkey = event.pubkey,
                createdAt = event.createdAt,
                receivedAt = event.createdAt,
                targetEventId = targetEventId,
                event = event,
                targetReference = resolved.reference,
            ),
        )
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
        saveKnownFollowers()
        if (!hasStoredFollowerList) return
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
        _state.update { state ->
            state.copy(items = (state.items + item).sortedByDescending { it.receivedAt }.take(MAX_ITEMS))
        }
        saveStoredNotifications()
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
        targetLoader.retain(_state.value.items.mapNotNull { it.targetEventId }.toSet())
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
                    targetEventId = (resolved.reference as? TargetReference.EventId)?.id,
                    targetReference = resolved.reference,
                ) to resolved.embedded
            }
        }
        val items = normalized.map { it.first }.filterNotMutedActors(muteStore?.mutedPubkeys?.value.orEmpty())
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
        if (items.size != stored.items.size) {
            LocalNotificationStore.save(
                ownPubkey,
                stored.copy(items = items),
            )
        }
    }

    private fun removeMutedNotifications(mutedPubkeys: Set<String>) {
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
    }

    private fun scheduleDetailsForItem(item: NotificationItem) {
        scheduleProfileFetch(item.actorPubkey)
        item.targetEventId?.let { scheduleTargetFetch(it) }
        item.targetEventId?.let { _state.value.targetEvents[it] }?.let { scheduleProfileFetch(it.pubkey) }
    }

    private fun saveStoredNotifications() {
        val snapshot = _state.value
        val stored = StoredNotifications(
            items = snapshot.items.sortedByDescending { it.receivedAt }.take(MAX_ITEMS),
            readItemIds = snapshot.readItemIds,
        )
        launch {
            LocalNotificationStore.save(ownPubkey, stored)
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

    private fun closeSubscriptions() {
        NostrRepository.close(activitySubId)
        NostrRepository.close(followsSubId)
    }

    companion object {
        private const val MAX_ITEMS = 100
        private const val MAX_SEEN_IDS = 1000
        private const val INITIAL_SYNC_LIMIT = 100
        private const val LIVE_SYNC_LIMIT = 100
        private const val INITIAL_SYNC_TIMEOUT_MS = 10_000L
        private const val AUXILIARY_FETCH_DRAIN_MS = 1_000L
        private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
    }
}

internal fun List<NotificationItem>.filterNotMutedActors(
    mutedPubkeys: Set<String>,
): List<NotificationItem> {
    if (mutedPubkeys.isEmpty()) return this
    val normalizedMutedPubkeys = mutedPubkeys.mapTo(hashSetOf()) { it.trim().lowercase() }
    return filterNot { it.actorPubkey.trim().lowercase() in normalizedMutedPubkeys }
}
