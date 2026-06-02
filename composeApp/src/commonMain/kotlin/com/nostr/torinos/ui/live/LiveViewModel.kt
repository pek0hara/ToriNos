package com.nostr.torinos.ui.live

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.LiveActivityItem
import com.nostr.torinos.model.LiveActivityStatus
import com.nostr.torinos.model.NIP53_LIVE_ACTIVITY_KIND
import com.nostr.torinos.model.NIP53_LIVE_CHAT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.latestLiveActivityVersions
import com.nostr.torinos.model.liveActivityAddress
import com.nostr.torinos.model.toLiveActivityMeta
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.ui.SafeViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveListState(
    val activities: List<LiveActivityItem> = emptyList(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val selectedStatuses: Set<LiveActivityStatus> = setOf(LiveActivityStatus.Live, LiveActivityStatus.Planned),
    val isInitialLoad: Boolean = true,
    val error: String? = null,
)

data class LiveDetailState(
    val activity: LiveActivityItem? = null,
    val chatMessages: List<NostrEvent> = emptyList(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val isInitialLoad: Boolean = true,
    val isPublishing: Boolean = false,
    val publishCompletedCount: Int = 0,
    val error: String? = null,
)

class LiveListViewModel(private val relayUrl: String? = null) : SafeViewModel() {
    private val _state = MutableStateFlow(LiveListState())
    val state: StateFlow<LiveListState> = _state.asStateFlow()

    private val instanceKey = nextInstanceKey()
    private val relayKey = relayUrl?.hashCode()?.toString() ?: "all"
    private val liveSubId = "live-list-$relayKey-$instanceKey"
    private val profileSubId = "live-list-profile-$relayKey-$instanceKey"
    private val rawEvents = linkedMapOf<String, NostrEvent>()
    private val pendingPubkeys = linkedSetOf<String>()
    private val jobs = mutableListOf<Job>()
    private var profileBatchJob: Job? = null

    init {
        start()
    }

    fun toggleStatus(status: LiveActivityStatus) {
        val current = _state.value.selectedStatuses
        _state.value = _state.value.copy(
            selectedStatuses = if (status in current) current - status else current + status,
        )
        rebuildActivities()
    }

    private fun start() {
        jobs += launch {
            NostrRepository.events(liveSubId).collect { event ->
                if (event.kind != NIP53_LIVE_ACTIVITY_KIND) return@collect
                rememberActivity(event)
            }
        }
        jobs += launch {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                pendingPubkeys.remove(event.pubkey)
                _state.value = _state.value.copy(profiles = _state.value.profiles + (event.pubkey to profile))
                rebuildActivities()
            }
        }
        jobs += launch {
            NostrRepository.eose(liveSubId).collect {
                _state.value = _state.value.copy(isInitialLoad = false)
            }
        }
        jobs += launch {
            delay(INITIAL_LOAD_TIMEOUT_MS)
            if (_state.value.isInitialLoad) {
                _state.value = _state.value.copy(isInitialLoad = false)
            }
        }
        jobs += launch {
            while (true) {
                delay(LIVE_REFRESH_INTERVAL_MS)
                rebuildActivities()
            }
        }
        jobs += launch {
            NostrRepository.subscribe(
                liveSubId,
                NostrFilter(kinds = listOf(NIP53_LIVE_ACTIVITY_KIND), limit = LIVE_LIMIT),
                relayUrl = relayUrl,
            )
        }
    }

    private fun rememberActivity(event: NostrEvent) {
        val meta = event.toLiveActivityMeta(Clock.System.now().epochSeconds) ?: return
        val address = liveActivityAddress(event.pubkey, meta.identifier)
        val existing = rawEvents[address]
        if (existing != null && existing.createdAt >= event.createdAt) return
        rawEvents[address] = event
        scheduleProfileFetch(event.pubkey)
        meta.participants.forEach { scheduleProfileFetch(it.pubkey) }
        rebuildActivities()
    }

    private fun rebuildActivities() {
        val now = Clock.System.now().epochSeconds
        val selected = _state.value.selectedStatuses
        val profiles = _state.value.profiles
        val activities = rawEvents.values
            .mapNotNull { event ->
                val meta = event.toLiveActivityMeta(now) ?: return@mapNotNull null
                LiveActivityItem(event = event, meta = meta, authorProfile = profiles[event.pubkey])
            }
            .latestLiveActivityVersions(now)
            .filter { selected.isEmpty() || it.meta.status in selected }
        _state.value = _state.value.copy(activities = activities)
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in _state.value.profiles || !pendingPubkeys.add(pubkey)) return
        profileBatchJob?.cancel()
        profileBatchJob = launch {
            delay(PROFILE_BATCH_DELAY_MS)
            val pubkeys = pendingPubkeys.toList().take(PROFILE_FETCH_LIMIT)
            pendingPubkeys.removeAll(pubkeys.toSet())
            if (pubkeys.isNotEmpty()) {
                NostrRepository.subscribe(profileSubId, NostrFilter(kinds = listOf(0), authors = pubkeys), relayUrl = relayUrl)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        jobs.forEach { it.cancel() }
        profileBatchJob?.cancel()
        NostrRepository.close(liveSubId)
        NostrRepository.close(profileSubId)
    }
}

class LiveDetailViewModel(
    private val pubkey: String,
    private val identifier: String,
    private val relayUrl: String? = null,
) : SafeViewModel() {
    private val _state = MutableStateFlow(LiveDetailState())
    val state: StateFlow<LiveDetailState> = _state.asStateFlow()

    private val instanceKey = nextInstanceKey()
    private val relayKey = relayUrl?.hashCode()?.toString() ?: "all"
    private val activitySubId = "live-detail-$relayKey-$instanceKey"
    private val chatSubId = "live-chat-$relayKey-$instanceKey"
    private val profileSubId = "live-detail-profile-$relayKey-$instanceKey"
    private val address = liveActivityAddress(pubkey, identifier)
    private val chatEvents = linkedMapOf<String, NostrEvent>()
    private val pendingPubkeys = linkedSetOf<String>()
    private val jobs = mutableListOf<Job>()
    private var profileBatchJob: Job? = null

    init {
        start()
    }

    fun publishChat(content: String) {
        val body = content.trim()
        if (body.isBlank()) {
            _state.value = _state.value.copy(error = "メッセージを入力してください")
            return
        }
        launch {
            _state.value = _state.value.copy(isPublishing = true, error = null)
            try {
                val privateKeyHex = KeyStorage.loadPrivateKey()
                    ?: error("秘密鍵が見つかりません")
                val event = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = body,
                    kind = NIP53_LIVE_CHAT_KIND,
                    tags = listOf(listOf("a", address, relayUrl.orEmpty(), "root")),
                )
                rememberChat(event)
                if (relayUrl != null) {
                    NostrRepository.publishToRelays(event, listOf(relayUrl))
                } else {
                    NostrRepository.publish(event)
                }
                _state.value = _state.value.copy(
                    isPublishing = false,
                    publishCompletedCount = _state.value.publishCompletedCount + 1,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    isPublishing = false,
                    error = e.message ?: "チャットの送信に失敗しました",
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun start() {
        jobs += launch {
            NostrRepository.events(activitySubId).collect { event ->
                if (event.kind != NIP53_LIVE_ACTIVITY_KIND || event.pubkey != pubkey) return@collect
                val meta = event.toLiveActivityMeta(Clock.System.now().epochSeconds) ?: return@collect
                if (meta.identifier != identifier) return@collect
                val existing = _state.value.activity?.event
                if (existing != null && existing.createdAt >= event.createdAt) return@collect
                scheduleProfileFetch(event.pubkey)
                meta.participants.forEach { scheduleProfileFetch(it.pubkey) }
                _state.value = _state.value.copy(
                    activity = LiveActivityItem(event, meta, _state.value.profiles[event.pubkey]),
                    isInitialLoad = false,
                )
            }
        }
        jobs += launch {
            NostrRepository.events(chatSubId).collect { event ->
                if (event.kind != NIP53_LIVE_CHAT_KIND) return@collect
                if (event.tags.none { it.firstOrNull() == "a" && it.getOrNull(1) == address }) return@collect
                rememberChat(event)
            }
        }
        jobs += launch {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                pendingPubkeys.remove(event.pubkey)
                _state.value = _state.value.copy(profiles = _state.value.profiles + (event.pubkey to profile))
                refreshProfilesOnItems()
            }
        }
        jobs += launch {
            NostrRepository.eose(activitySubId).collect {
                _state.value = _state.value.copy(isInitialLoad = false)
            }
        }
        jobs += launch {
            delay(INITIAL_LOAD_TIMEOUT_MS)
            if (_state.value.isInitialLoad) {
                _state.value = _state.value.copy(isInitialLoad = false)
            }
        }
        jobs += launch {
            NostrRepository.subscribe(
                activitySubId,
                NostrFilter(kinds = listOf(NIP53_LIVE_ACTIVITY_KIND), authors = listOf(pubkey), dTags = listOf(identifier), limit = 5),
                relayUrl = relayUrl,
            )
            NostrRepository.subscribe(
                chatSubId,
                NostrFilter(kinds = listOf(NIP53_LIVE_CHAT_KIND), aTags = listOf(address), limit = CHAT_LIMIT),
                relayUrl = relayUrl,
            )
        }
    }

    private fun rememberChat(event: NostrEvent) {
        if (chatEvents.containsKey(event.id)) return
        chatEvents[event.id] = event
        scheduleProfileFetch(event.pubkey)
        _state.value = _state.value.copy(
            chatMessages = chatEvents.values.sortedBy { it.createdAt },
        )
    }

    private fun refreshProfilesOnItems() {
        val activity = _state.value.activity
        _state.value = _state.value.copy(
            activity = activity?.copy(authorProfile = _state.value.profiles[activity.event.pubkey]),
        )
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in _state.value.profiles || !pendingPubkeys.add(pubkey)) return
        profileBatchJob?.cancel()
        profileBatchJob = launch {
            delay(PROFILE_BATCH_DELAY_MS)
            val pubkeys = pendingPubkeys.toList().take(PROFILE_FETCH_LIMIT)
            pendingPubkeys.removeAll(pubkeys.toSet())
            if (pubkeys.isNotEmpty()) {
                NostrRepository.subscribe(profileSubId, NostrFilter(kinds = listOf(0), authors = pubkeys), relayUrl = relayUrl)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        jobs.forEach { it.cancel() }
        profileBatchJob?.cancel()
        NostrRepository.close(activitySubId)
        NostrRepository.close(chatSubId)
        NostrRepository.close(profileSubId)
    }
}

private const val LIVE_LIMIT = 200
private const val CHAT_LIMIT = 500
private const val PROFILE_FETCH_LIMIT = 200
private const val INITIAL_LOAD_TIMEOUT_MS = 5_000L
private const val PROFILE_BATCH_DELAY_MS = 300L
private const val LIVE_REFRESH_INTERVAL_MS = 60_000L

private var nextKey = 0
private fun nextInstanceKey(): Int = nextKey++
