package com.nostr.torinos.ui.thread

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ThreadViewModel(
    private val eventId: String,
) : SafeViewModel() {

    data class UiState(
        val root: NostrEvent? = null,
        val replies: List<NostrEvent> = emptyList(),
        val profiles: Map<String, NostrProfile> = emptyMap(),
        val replyCounts: Map<String, Int> = emptyMap(),
        val reactionPubkeys: List<String> = emptyList(),
        val repostPubkeys: List<String> = emptyList(),
        val isLoading: Boolean = true,
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(UiState())
    val state: kotlinx.coroutines.flow.StateFlow<UiState> = _state

    private val shortId = eventId.take(16)
    private val rootSubId = "thread-root-$shortId"
    private val repliesSubId = "thread-replies-$shortId"
    private val profileSubId = "thread-prof-$shortId"
    private val replyCountSubId = "thread-count-$shortId"
    private val reactionSubId = "thread-react-$shortId"
    private val repostSubId = "thread-repost-$shortId"

    private val subscriptionJobs = mutableListOf<Job>()
    private val seenReplyIds = linkedSetOf<String>()
    private val seenReplyCountIds = linkedSetOf<String>()
    private val seenReactionIds = linkedSetOf<String>()
    private val seenRepostIds = linkedSetOf<String>()
    private val watchedEventIds = linkedSetOf<String>()
    private val pendingPubkeys = linkedSetOf<String>()
    private var profileBatchJob: Job? = null
    private var replyCountBatchJob: Job? = null
    private var started = false

    init {
        startSubscriptions()
    }

    fun startSubscriptions() {
        if (started) return
        started = true

        scheduleReplyCountFetch(eventId)

        subscriptionJobs += launch {
            NostrRepository.subscribe(rootSubId, NostrFilter(ids = listOf(eventId), kinds = listOf(1), limit = 1))
            NostrRepository.subscribe(repliesSubId, NostrFilter(kinds = listOf(1), eTags = listOf(eventId), limit = 100))
            NostrRepository.subscribe(reactionSubId, NostrFilter(kinds = listOf(7), eTags = listOf(eventId), limit = 500))
            NostrRepository.subscribe(repostSubId, NostrFilter(kinds = listOf(6), eTags = listOf(eventId), limit = 500))
        }

        subscriptionJobs += launch {
            NostrRepository.events(rootSubId).collect { event ->
                if (event.kind != 1 || event.id != eventId) return@collect
                _state.value = _state.value.copy(root = event, isLoading = false)
                scheduleProfileFetch(event.pubkey)
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(repliesSubId).collect { event ->
                if (event.kind != 1 || !seenReplyIds.add(event.id)) return@collect
                if (event.replyTargetId() != eventId) {
                    scheduleReplyCountFetch(event.replyTargetId() ?: return@collect)
                    return@collect
                }
                val cur = _state.value
                _state.value = cur.copy(
                    replies = (cur.replies + event).sortedBy { it.createdAt },
                    isLoading = false,
                )
                scheduleProfileFetch(event.pubkey)
                scheduleReplyCountFetch(event.id)
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                pendingPubkeys.remove(event.pubkey)
                _state.value = _state.value.copy(
                    profiles = _state.value.profiles + (event.pubkey to profile),
                )
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(replyCountSubId).collect { event ->
                if (event.kind != 1 || !seenReplyCountIds.add(event.id)) return@collect
                val targetId = event.replyTargetId() ?: return@collect
                val cur = _state.value
                _state.value = cur.copy(
                    replyCounts = cur.replyCounts + (targetId to (cur.replyCounts[targetId] ?: 0) + 1),
                )
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(reactionSubId).collect { event ->
                if (event.kind != 7 || !seenReactionIds.add(event.id)) return@collect
                if (event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1) != eventId) return@collect
                val cur = _state.value
                if (event.pubkey in cur.reactionPubkeys) return@collect
                _state.value = cur.copy(reactionPubkeys = cur.reactionPubkeys + event.pubkey)
                scheduleProfileFetch(event.pubkey)
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(repostSubId).collect { event ->
                if (event.kind != 6 || !seenRepostIds.add(event.id)) return@collect
                if (event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1) != eventId) return@collect
                val cur = _state.value
                if (event.pubkey in cur.repostPubkeys) return@collect
                _state.value = cur.copy(repostPubkeys = cur.repostPubkeys + event.pubkey)
                scheduleProfileFetch(event.pubkey)
            }
        }

        subscriptionJobs += launch {
            delay(10_000)
            if (_state.value.isLoading) {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    fun stopSubscriptions() {
        if (!started) return
        started = false
        subscriptionJobs.forEach { it.cancel() }
        subscriptionJobs.clear()
        profileBatchJob?.cancel()
        replyCountBatchJob?.cancel()
        NostrRepository.close(rootSubId)
        NostrRepository.close(repliesSubId)
        NostrRepository.close(profileSubId)
        NostrRepository.close(replyCountSubId)
        NostrRepository.close(reactionSubId)
        NostrRepository.close(repostSubId)
    }

    override fun onCleared() {
        super.onCleared()
        stopSubscriptions()
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in _state.value.profiles || !pendingPubkeys.add(pubkey)) return
        profileBatchJob?.cancel()
        profileBatchJob = launch {
            delay(300)
            if (pendingPubkeys.isEmpty()) return@launch
            val authors = pendingPubkeys.toList()
            pendingPubkeys.removeAll(authors)
            NostrRepository.subscribe(profileSubId, NostrFilter(kinds = listOf(0), authors = authors))
        }
    }

    private fun scheduleReplyCountFetch(eventId: String) {
        if (!watchedEventIds.add(eventId)) return
        while (watchedEventIds.size > MAX_WATCHED_EVENTS) watchedEventIds.remove(watchedEventIds.first())
        replyCountBatchJob?.cancel()
        replyCountBatchJob = launch {
            delay(300)
            NostrRepository.subscribe(replyCountSubId, NostrFilter(kinds = listOf(1), eTags = watchedEventIds.toList()))
        }
    }

    private fun NostrEvent.replyTargetId(): String? =
        tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)

    companion object {
        private const val MAX_WATCHED_EVENTS = 100
    }
}
