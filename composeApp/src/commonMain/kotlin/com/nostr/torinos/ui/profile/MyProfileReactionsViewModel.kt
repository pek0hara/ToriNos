package com.nostr.torinos.ui.profile

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.TargetEventLoader
import com.nostr.torinos.network.TargetLoadState
import com.nostr.torinos.ui.notification.TargetReference
import com.nostr.torinos.ui.notification.resolveNotificationTarget
import androidx.lifecycle.viewModelScope
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

data class MyProfileReactionItem(
    val id: String,
    val type: MyProfileReactionType,
    val actorPubkey: String,
    val createdAt: Long,
    val targetEventId: String?,
    val event: NostrEvent,
    val targetReference: TargetReference = TargetReference.None,
)

enum class MyProfileReactionType {
    Reply,
    Repost,
    Like,
}

data class MyProfileReactionsState(
    val items: List<MyProfileReactionItem> = emptyList(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val targetStates: Map<String, TargetLoadState> = emptyMap(),
    val isInitialLoad: Boolean = true,
) {
    val targetEvents: Map<String, NostrEvent>
        get() = targetStates.mapNotNull { (id, state) ->
            (state as? TargetLoadState.Resolved)?.let { id to it.event }
        }.toMap()
}

class MyProfileReactionsViewModel(private val ownPubkey: String) : SafeViewModel() {
    private val _state = MutableStateFlow(MyProfileReactionsState())
    val state: StateFlow<MyProfileReactionsState> = _state.asStateFlow()

    private val reactionsSubId = "mp-react-$shortKey"
    private val targetLoader = TargetEventLoader(viewModelScope)

    private val seenItemIds = linkedSetOf<String>()
    private val requestedProfilePubkeys = linkedSetOf<String>()
    private val collectorJobs = mutableListOf<Job>()
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
            ProfileRepository.observeAll().collect { cachedProfiles ->
                val profiles = cachedProfiles.filterKeys { it in requestedProfilePubkeys }
                if (profiles != _state.value.profiles) {
                    _state.update { it.copy(profiles = profiles) }
                }
            }
        }
        collectorJobs += launch {
            targetLoader.states.collect { targets ->
                val previous = _state.value.targetStates
                _state.update { it.copy(targetStates = targets) }
                targets.forEach { (id, state) ->
                    if (state is TargetLoadState.Resolved && previous[id] != state) scheduleProfileFetch(state.event.pubkey)
                }
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

    private suspend fun handleReactionEvent(event: NostrEvent) {
        if (event.pubkey == ownPubkey) return
        val type = when (event.kind) {
            1 -> MyProfileReactionType.Reply
            6 -> MyProfileReactionType.Repost
            7 -> MyProfileReactionType.Like
            else -> return
        }
        if (!rememberSeenId(event.id)) return

        val resolved = withContext(Dispatchers.Default) { resolveNotificationTarget(event) }
        val targetEventId = (resolved.reference as? TargetReference.EventId)?.id
        val item = MyProfileReactionItem(
            id = event.id,
            type = type,
            actorPubkey = event.pubkey,
            createdAt = event.createdAt,
            targetEventId = targetEventId,
            event = event,
            targetReference = resolved.reference,
        )
        _state.update { state ->
            state.copy(items = (state.items + item).sortedByDescending { it.createdAt }.take(100))
        }
        scheduleProfileFetch(event.pubkey)
        resolved.embedded?.let(targetLoader::seedValidated)
        if (targetEventId != null) targetLoader.request(targetEventId)
        targetLoader.retain(_state.value.items.mapNotNull { it.targetEventId }.toSet())
    }

    private fun scheduleProfileFetch(pubkey: String) {
        requestedProfilePubkeys.add(pubkey)
        ProfileRepository.getCached(pubkey)?.let { cachedProfile ->
            _state.update { it.copy(profiles = it.profiles + (pubkey to cachedProfile)) }
        }
        launch {
            ProfileRepository.ensureProfiles(
                setOf(pubkey),
                ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS),
            )
        }
    }

    fun retryTarget(eventId: String) {
        if (_state.value.items.any { it.targetEventId == eventId }) targetLoader.request(eventId, retry = true)
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
        requestedProfilePubkeys.clear()
        targetLoader.stop()
        eoseJob?.cancel()
        NostrRepository.close(reactionsSubId)
    }

    companion object {
        private const val MAX_SEEN_IDS = 1000
        private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
    }
}
