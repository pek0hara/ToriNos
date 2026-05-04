package com.nostr.torinos.ui.thread

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.crypto.loadPublicKey
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class ThreadViewModel(
    private val eventId: String,
) : SafeViewModel() {

    data class UiState(
        val root: NostrEvent? = null,
        val replies: List<NostrEvent> = emptyList(),
        val profiles: Map<String, NostrProfile> = emptyMap(),
        val replyCounts: Map<String, Int> = emptyMap(),
        val reactionCounts: Map<String, Int> = emptyMap(),
        val reactionPubkeys: List<String> = emptyList(),
        val repostPubkeys: List<String> = emptyList(),
        val likedReactions: Map<String, String> = emptyMap(),
        val ownRepostEventId: String? = null,
        val isLoading: Boolean = true,
        val quotedEvents: Map<String, NostrEvent> = emptyMap(),
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
    private val replyParentSubId = "thread-parent-$shortId"

    private val subscriptionJobs = mutableListOf<Job>()
    private val seenReplyIds = linkedSetOf<String>()
    private val seenReplyCountIds = linkedSetOf<String>()
    private val seenReactionIds = linkedSetOf<String>()
    private val seenRepostIds = linkedSetOf<String>()
    private val watchedEventIds = linkedSetOf<String>()
    private val watchedReactionEventIds = linkedSetOf<String>()
    private val pendingPubkeys = linkedSetOf<String>()
    private var profileBatchJob: Job? = null
    private var replyCountBatchJob: Job? = null
    private var reactionBatchJob: Job? = null
    private var started = false
    private var ownPubkey: String? = null

    init {
        if (isWriteSupported) launch { ownPubkey = loadPublicKey() }
        startSubscriptions()
    }

    fun react(eventId: String, eventPubkey: String) {
        val cur = _state.value
        if (cur.likedReactions.containsKey(eventId)) return
        _state.value = cur.copy(
            likedReactions = cur.likedReactions + (eventId to ""),
            reactionCounts = cur.reactionCounts + (eventId to (cur.reactionCounts[eventId] ?: 0) + 1),
            reactionPubkeys = if (eventId == this.eventId && ownPubkey != null && ownPubkey !in cur.reactionPubkeys) {
                cur.reactionPubkeys + ownPubkey!!
            } else {
                cur.reactionPubkeys
            },
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
                seenReactionIds.add(reaction.id)
                NostrRepository.publish(reaction)
                _state.value = _state.value.copy(
                    likedReactions = _state.value.likedReactions + (eventId to reaction.id),
                )
            }
        }
    }

    fun unreact(eventId: String) {
        val cur = _state.value
        val reactionEventId = cur.likedReactions[eventId] ?: return
        _state.value = cur.copy(
            likedReactions = cur.likedReactions - eventId,
            reactionCounts = cur.reactionCounts + (eventId to maxOf(0, (cur.reactionCounts[eventId] ?: 0) - 1)),
            reactionPubkeys = if (eventId == this.eventId && ownPubkey != null) {
                cur.reactionPubkeys - ownPubkey!!
            } else {
                cur.reactionPubkeys
            },
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
        if (cur.ownRepostEventId != null) return
        val currentOwnPubkey = ownPubkey
        _state.value = cur.copy(
            repostPubkeys = if (currentOwnPubkey != null && currentOwnPubkey !in cur.repostPubkeys) {
                cur.repostPubkeys + currentOwnPubkey
            } else {
                cur.repostPubkeys
            },
            ownRepostEventId = "",
        )
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: return@launch
            runCatching {
                val repostEvent = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = Json.encodeToString(NostrEvent.serializer(), event),
                    kind = 6,
                    tags = listOf(listOf("e", event.id), listOf("p", event.pubkey)),
                )
                seenRepostIds.add(repostEvent.id)
                NostrRepository.publish(repostEvent)
                _state.value = _state.value.copy(ownRepostEventId = repostEvent.id)
            }
        }
    }

    fun unrepost() {
        val cur = _state.value
        val repostEventId = cur.ownRepostEventId ?: return
        val currentOwnPubkey = ownPubkey
        _state.value = cur.copy(
            repostPubkeys = if (currentOwnPubkey != null) {
                cur.repostPubkeys - currentOwnPubkey
            } else {
                cur.repostPubkeys
            },
            ownRepostEventId = null,
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

    fun startSubscriptions() {
        if (started) return
        started = true

        scheduleReplyCountFetch(eventId)
        scheduleReactionFetch(eventId)

        subscriptionJobs += launch {
            NostrRepository.subscribe(rootSubId, NostrFilter(ids = listOf(eventId), kinds = listOf(1), limit = 1))
            NostrRepository.subscribe(repliesSubId, NostrFilter(kinds = listOf(1), eTags = listOf(eventId), limit = 100))
            NostrRepository.subscribe(repostSubId, NostrFilter(kinds = listOf(6), eTags = listOf(eventId), limit = 500))
        }

        subscriptionJobs += launch {
            NostrRepository.events(rootSubId).collect { event ->
                if (event.kind != 1 || event.id != eventId) return@collect
                _state.value = _state.value.copy(root = event, isLoading = false)
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
                event.replyTargetId()?.let { parentId ->
                    NostrRepository.subscribe(
                        replyParentSubId,
                        NostrFilter(ids = listOf(parentId), kinds = listOf(1), limit = 1),
                    )
                }
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(replyParentSubId).collect { event ->
                if (event.kind != 1) return@collect
                val cur = _state.value
                if (cur.quotedEvents.containsKey(event.id)) return@collect
                _state.value = cur.copy(quotedEvents = cur.quotedEvents + (event.id to event))
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
                scheduleMentionedProfileFetch(event.content)
                scheduleReplyCountFetch(event.id)
                scheduleReactionFetch(event.id)
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
                val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                    ?: return@collect
                if (targetId !in watchedReactionEventIds) return@collect
                val cur = _state.value
                val isOwn = ownPubkey != null && event.pubkey == ownPubkey
                val rootReactionPubkeys = if (
                    targetId == eventId &&
                    event.pubkey !in cur.reactionPubkeys
                ) {
                    cur.reactionPubkeys + event.pubkey
                } else {
                    cur.reactionPubkeys
                }
                _state.value = cur.copy(
                    reactionCounts = cur.reactionCounts + (targetId to (cur.reactionCounts[targetId] ?: 0) + 1),
                    reactionPubkeys = rootReactionPubkeys,
                    likedReactions = if (isOwn && !cur.likedReactions.containsKey(targetId)) {
                        cur.likedReactions + (targetId to event.id)
                    } else {
                        cur.likedReactions
                    },
                )
                scheduleProfileFetch(event.pubkey)
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(repostSubId).collect { event ->
                if (event.kind != 6 || !seenRepostIds.add(event.id)) return@collect
                if (event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1) != eventId) return@collect
                val cur = _state.value
                val ownRepostEventId = if (ownPubkey != null && event.pubkey == ownPubkey) {
                    event.id
                } else {
                    cur.ownRepostEventId
                }
                if (event.pubkey in cur.repostPubkeys) {
                    if (ownRepostEventId != cur.ownRepostEventId) {
                        _state.value = cur.copy(ownRepostEventId = ownRepostEventId)
                    }
                    return@collect
                }
                _state.value = cur.copy(
                    repostPubkeys = cur.repostPubkeys + event.pubkey,
                    ownRepostEventId = ownRepostEventId,
                )
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
        reactionBatchJob?.cancel()
        NostrRepository.close(rootSubId)
        NostrRepository.close(repliesSubId)
        NostrRepository.close(profileSubId)
        NostrRepository.close(replyCountSubId)
        NostrRepository.close(reactionSubId)
        NostrRepository.close(repostSubId)
        NostrRepository.close(replyParentSubId)
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

    private fun scheduleMentionedProfileFetch(text: String) {
        extractNpubReferences(text).forEach { reference ->
            scheduleProfileFetch(reference.pubkey)
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

    private fun scheduleReactionFetch(eventId: String) {
        if (!watchedReactionEventIds.add(eventId)) return
        while (watchedReactionEventIds.size > MAX_WATCHED_EVENTS) {
            watchedReactionEventIds.remove(watchedReactionEventIds.first())
        }
        reactionBatchJob?.cancel()
        reactionBatchJob = launch {
            delay(300)
            NostrRepository.subscribe(
                reactionSubId,
                NostrFilter(kinds = listOf(7), eTags = watchedReactionEventIds.toList(), limit = 500),
            )
        }
    }

    companion object {
        private const val MAX_WATCHED_EVENTS = 100
    }
}
