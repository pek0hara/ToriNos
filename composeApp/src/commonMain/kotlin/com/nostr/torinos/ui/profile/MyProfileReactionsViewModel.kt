package com.nostr.torinos.ui.profile

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

data class MyProfileReactionItem(
    val id: String,
    val type: MyProfileReactionType,
    val actorPubkey: String,
    val createdAt: Long,
    val targetEventId: String?,
    val event: NostrEvent,
)

enum class MyProfileReactionType {
    Reply,
    Repost,
    Like,
}

data class MyProfileReactionsState(
    val items: List<MyProfileReactionItem> = emptyList(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val targetEvents: Map<String, NostrEvent> = emptyMap(),
    val isInitialLoad: Boolean = true,
)

class MyProfileReactionsViewModel(private val ownPubkey: String) : SafeViewModel() {
    private val _state = MutableStateFlow(MyProfileReactionsState())
    val state: StateFlow<MyProfileReactionsState> = _state.asStateFlow()

    private val reactionsSubId = "mp-react-$shortKey"
    private val profileSubId = "mp-react-prof-$shortKey"
    private val targetSubId = "mp-react-target-$shortKey"

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
            NostrRepository.events(reactionsSubId).collect { event ->
                handleReactionEvent(event)
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
                NostrRepository.eose(reactionsSubId).first()
            }
            _state.update { it.copy(isInitialLoad = false) }
        }
        launch {
            NostrRepository.subscribe(
                reactionsSubId,
                NostrFilter(kinds = listOf(1, 6, 7), pTags = listOf(ownPubkey), limit = 100),
            )
        }
    }

    private fun handleReactionEvent(event: NostrEvent) {
        if (event.pubkey == ownPubkey) return
        val type = when (event.kind) {
            1 -> MyProfileReactionType.Reply
            6 -> MyProfileReactionType.Repost
            7 -> MyProfileReactionType.Like
            else -> return
        }
        if (!rememberSeenId(event.id)) return

        val targetEventId = event.targetEventId() ?: event.embeddedRepostTarget()?.id
        val item = MyProfileReactionItem(
            id = event.id,
            type = type,
            actorPubkey = event.pubkey,
            createdAt = event.createdAt,
            targetEventId = targetEventId,
            event = event,
        )
        _state.update { state ->
            state.copy(items = (state.items + item).sortedByDescending { it.createdAt })
        }
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
            NostrRepository.subscribe(
                targetSubId,
                NostrFilter(ids = pendingTargetIds.toList(), kinds = listOf(1)),
            )
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
        NostrRepository.close(reactionsSubId)
        NostrRepository.close(profileSubId)
        NostrRepository.close(targetSubId)
    }

    companion object {
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
