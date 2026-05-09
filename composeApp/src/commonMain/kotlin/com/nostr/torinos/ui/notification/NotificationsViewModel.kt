package com.nostr.torinos.ui.notification

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

data class NotificationItem(
    val id: String,
    val type: NotificationType,
    val actorPubkey: String,
    val createdAt: Long,
    val targetEventId: String?,
    val event: NostrEvent,
)

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
    val targetEvents: Map<String, NostrEvent> = emptyMap(),
    val isInitialLoad: Boolean = true,
) {
    val hasUnread: Boolean
        get() = items.any { it.id !in readItemIds }
}

class NotificationsViewModel(private val ownPubkey: String) : SafeViewModel() {
    private val _state = MutableStateFlow(NotificationsState())
    val state: StateFlow<NotificationsState> = _state.asStateFlow()

    private val activitySubId = "notif-act-$shortKey"
    private val followsSubId = "notif-follow-$shortKey"
    private val profileSubId = "notif-prof-$shortKey"
    private val targetSubId = "notif-target-$shortKey"

    private val seenItemIds = linkedSetOf<String>()
    private val pendingPubkeys = linkedSetOf<String>()
    private val pendingTargetIds = linkedSetOf<String>()
    private val collectorJobs = mutableListOf<Job>()
    private var profileBatchJob: Job? = null
    private var targetBatchJob: Job? = null
    private var eoseJob: Job? = null

    private val shortKey: String
        get() = ownPubkey.take(16)

    init {
        start()
    }

    private fun start() {
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
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                pendingPubkeys.remove(event.pubkey)
                _state.update { it.copy(profiles = it.profiles + (event.pubkey to profile)) }
            }
        }
        collectorJobs += launch {
            NostrRepository.events(targetSubId).collect { event ->
                if (event.kind != 1) return@collect
                pendingTargetIds.remove(event.id)
                _state.update { it.copy(targetEvents = it.targetEvents + (event.id to event)) }
                scheduleProfileFetch(event.pubkey)
            }
        }
        eoseJob = launch {
            withTimeoutOrNull(10_000) {
                val activityEose = async { NostrRepository.eose(activitySubId).first() }
                val followsEose = async { NostrRepository.eose(followsSubId).first() }
                activityEose.await()
                followsEose.await()
            }
            _state.update { it.copy(isInitialLoad = false) }
        }
        launch {
            NostrRepository.subscribe(
                activitySubId,
                NostrFilter(kinds = listOf(1, 6, 7), pTags = listOf(ownPubkey), limit = 100),
            )
            NostrRepository.subscribe(
                followsSubId,
                NostrFilter(kinds = listOf(3), pTags = listOf(ownPubkey), limit = 100),
            )
        }
    }

    private fun handleActivityEvent(event: NostrEvent) {
        if (event.pubkey == ownPubkey) return
        val type = when (event.kind) {
            1 -> NotificationType.Reply
            6 -> NotificationType.Repost
            7 -> NotificationType.Like
            else -> return
        }
        if (!rememberSeenId(event.id)) return

        val targetEventId = event.targetEventId() ?: event.embeddedRepostTarget()?.id
        addItem(
            NotificationItem(
                id = event.id,
                type = type,
                actorPubkey = event.pubkey,
                createdAt = event.createdAt,
                targetEventId = targetEventId,
                event = event,
            ),
        )
        scheduleProfileFetch(event.pubkey)
        if (targetEventId != null) {
            scheduleTargetFetch(targetEventId)
        }
        event.embeddedRepostTarget()?.let { target ->
            _state.update { state ->
                state.copy(targetEvents = state.targetEvents + (target.id to target))
            }
            scheduleProfileFetch(target.pubkey)
        }
    }

    private fun handleFollowEvent(event: NostrEvent) {
        if (event.pubkey == ownPubkey) return
        if (!event.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == ownPubkey }) return
        if (!rememberSeenId(event.id)) return

        addItem(
            NotificationItem(
                id = event.id,
                type = NotificationType.Follow,
                actorPubkey = event.pubkey,
                createdAt = event.createdAt,
                targetEventId = null,
                event = event,
            ),
        )
        scheduleProfileFetch(event.pubkey)
    }

    private fun addItem(item: NotificationItem) {
        _state.update { state ->
            state.copy(items = (state.items + item).sortedByDescending { it.createdAt }.take(MAX_ITEMS))
        }
    }

    fun markAllRead() {
        _state.update { state ->
            state.copy(readItemIds = state.readItemIds + state.items.map { it.id })
        }
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in _state.value.profiles || !pendingPubkeys.add(pubkey)) return
        profileBatchJob?.cancel()
        profileBatchJob = launch {
            delay(400)
            val pubkeys = pendingPubkeys.toList()
            pendingPubkeys.clear()
            NostrRepository.subscribe(profileSubId, NostrFilter(kinds = listOf(0), authors = pubkeys))
        }
    }

    private fun scheduleTargetFetch(eventId: String) {
        if (eventId in _state.value.targetEvents || !pendingTargetIds.add(eventId)) return
        targetBatchJob?.cancel()
        targetBatchJob = launch {
            delay(400)
            val eventIds = pendingTargetIds.toList()
            pendingTargetIds.clear()
            NostrRepository.subscribe(targetSubId, NostrFilter(ids = eventIds, kinds = listOf(1)))
        }
    }

    private fun rememberSeenId(eventId: String): Boolean {
        if (!seenItemIds.add(eventId)) return false
        while (seenItemIds.size > MAX_SEEN_IDS) {
            seenItemIds.remove(seenItemIds.first())
        }
        return true
    }

    override fun onCleared() {
        super.onCleared()
        collectorJobs.forEach { it.cancel() }
        profileBatchJob?.cancel()
        targetBatchJob?.cancel()
        eoseJob?.cancel()
        NostrRepository.close(activitySubId)
        NostrRepository.close(followsSubId)
        NostrRepository.close(profileSubId)
        NostrRepository.close(targetSubId)
    }

    companion object {
        private const val MAX_ITEMS = 100
        private const val MAX_SEEN_IDS = 1000
    }
}

private fun NostrEvent.targetEventId(): String? =
    tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)

private fun NostrEvent.embeddedRepostTarget(): NostrEvent? {
    if (kind != 6 || content.isBlank()) return null
    return runCatching {
        Json.decodeFromString(NostrEvent.serializer(), content)
    }.getOrNull()
}
