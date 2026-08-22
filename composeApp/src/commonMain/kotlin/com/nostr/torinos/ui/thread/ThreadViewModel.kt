package com.nostr.torinos.ui.thread

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.AccountSessions
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.decrementedWith
import com.nostr.torinos.model.decrementedWithUnicodeReaction
import com.nostr.torinos.model.eventTags
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.incrementedWithUnicodeReaction
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toUnicodeReaction
import com.nostr.torinos.model.toReactionOption
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class ThreadViewModel(
    private val eventId: String,
    private val noteContext: NoteContext = NoteContext.Timeline,
    private val accountSession: AccountSession? = AccountSessions.manager.currentSession,
) : SafeViewModel() {

    data class UiState(
        val root: NostrEvent? = null,
        val replies: List<NostrEvent> = emptyList(),
        val repliesByEventId: Map<String, List<NostrEvent>> = emptyMap(),
        val profiles: Map<String, NostrProfile> = emptyMap(),
        val replyCounts: Map<String, Int> = emptyMap(),
        val reactionCounts: Map<String, Int> = emptyMap(),
        val likeReactionCounts: Map<String, Int> = emptyMap(),
        val customReactions: Map<String, List<CustomReaction>> = emptyMap(),
        val unicodeReactions: Map<String, List<UnicodeReaction>> = emptyMap(),
        val reactionPubkeys: List<String> = emptyList(),
        val rootReactionsByPubkey: Map<String, NostrEvent> = emptyMap(),
        val repostPubkeys: List<String> = emptyList(),
        val quoteReposts: List<NostrEvent> = emptyList(),
        val repostCount: Int = 0,
        val likedReactions: Map<String, String> = emptyMap(),
        val ownEmojiReactionEventIds: Map<String, Map<String, String>> = emptyMap(),
        val ownRepostEventId: String? = null,
        val isLoading: Boolean = true,
        val quotedEvents: Map<String, NostrEvent> = emptyMap(),
        val replyText: String = "",
        val isReplying: Boolean = false,
        val replyError: String? = null,
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(UiState())
    val state: kotlinx.coroutines.flow.StateFlow<UiState> = _state

    private val sessionKey = accountSession?.sessionId?.hashCode()?.toString() ?: "anonymous"
    private val shortId = "${eventId.take(16)}-$sessionKey"
    private val rootSubId = "thread-root-$shortId"
    private val repliesSubId = "thread-replies-$shortId"
    private val replyCountSubId = "thread-count-$shortId"
    private val reactionSubId = "thread-react-$shortId"
    private val repostSubId = "thread-repost-$shortId"
    private val quoteRepostSubId = "thread-qrepost-$shortId"
    private val replyParentSubId = "thread-parent-$shortId"
    private val quotedEventSubId = "thread-quote-$shortId"

    private val subscriptionJobs = mutableListOf<Job>()
    private val seenReplyIds = linkedSetOf<String>()
    private val seenReplyCountIds = linkedSetOf<String>()
    private val seenReactionIds = linkedSetOf<String>()
    private val seenRepostIds = linkedSetOf<String>()
    private val seenQuoteRepostIds = linkedSetOf<String>()
    private val receivedReactionEvents = linkedMapOf<String, NostrEvent>()
    private val receivedRepostEvents = linkedMapOf<String, NostrEvent>()
    private val watchedEventIds = linkedSetOf<String>()
    private val watchedReactionEventIds = linkedSetOf<String>()
    private val requestedProfilePubkeys = linkedSetOf<String>()
    private val pendingQuotedEventIds = linkedSetOf<String>()
    private var replyCountBatchJob: Job? = null
    private var reactionBatchJob: Job? = null
    private var started = false
    private var ownPubkey: String? = null

    init {
        if (isWriteSupported) {
            launch {
                ownPubkey = accountSession?.pubkey
                reconcileOwnEngagement()
            }
        }
        subscriptionJobs += launch {
            ProfileRepository.observeAll().collect { cachedProfiles ->
                val profiles = cachedProfiles.filterKeys { it in requestedProfilePubkeys }
                if (profiles != _state.value.profiles) {
                    _state.value = _state.value.copy(profiles = profiles)
                }
            }
        }
        startSubscriptions()
    }

    fun onReplyTextChange(text: String) {
        _state.value = _state.value.copy(replyText = text, replyError = null)
    }

    fun submitReply() {
        val root = _state.value.root ?: return
        val text = _state.value.replyText.trim()
        if (text.isBlank()) return
        _state.value = _state.value.copy(isReplying = true, replyError = null)
        launch {
            val signer = accountSession?.signer ?: run {
                _state.value = _state.value.copy(isReplying = false, replyError = "秘密鍵が設定されていません")
                return@launch
            }
            runCatching {
                val tags = noteContext.replyTags(root.id, root.pubkey) + listOf(listOf("client", "ToriNos"))
                val event = signer.sign(text, kind = noteContext.eventKind, tags = tags)
                NostrRepository.publish(event)
                event
            }.onSuccess { event ->
                rememberPublishedReply(event)
                _state.value = _state.value.copy(isReplying = false, replyText = "")
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isReplying = false,
                    replyError = e.message ?: "返信に失敗しました",
                )
            }
        }
    }

    fun react(eventId: String, eventPubkey: String) {
        val cur = _state.value
        if (
            cur.likedReactions.containsKey(eventId) ||
            cur.ownEmojiReactionEventIds[eventId].orEmpty().isNotEmpty()
        ) return
        _state.value = cur.copy(
            likedReactions = cur.likedReactions + (eventId to ""),
            reactionCounts = cur.reactionCounts + (eventId to (cur.reactionCounts[eventId] ?: 0) + 1),
            likeReactionCounts = cur.likeReactionCounts +
                (eventId to (cur.likeReactionCounts[eventId] ?: 0) + 1),
            reactionPubkeys = if (eventId == this.eventId && ownPubkey != null && ownPubkey !in cur.reactionPubkeys) {
                cur.reactionPubkeys + ownPubkey!!
            } else {
                cur.reactionPubkeys
            },
        )
        launch {
            val signer = accountSession?.signer ?: run {
                _state.value = _state.value.withoutOptimisticLike(eventId, ownPubkey)
                return@launch
            }
            runCatching {
                val reaction = signer.sign(
                    content = "+",
                    kind = 7,
                    tags = listOf(listOf("e", eventId), listOf("p", eventPubkey)),
                )
                seenReactionIds.add(reaction.id)
                NostrRepository.publish(reaction)
                _state.value = _state.value.copy(
                    likedReactions = _state.value.likedReactions + (eventId to reaction.id),
                    rootReactionsByPubkey = if (eventId == this@ThreadViewModel.eventId) {
                        _state.value.rootReactionsByPubkey + (reaction.pubkey to reaction)
                    } else {
                        _state.value.rootReactionsByPubkey
                    },
                )
            }.onFailure {
                _state.value = _state.value.withoutOptimisticLike(eventId, ownPubkey)
            }
        }
    }

    fun unreact(eventId: String) {
        val cur = _state.value
        val reactionEventId = cur.likedReactions[eventId] ?: return
        _state.value = cur.copy(
            likedReactions = cur.likedReactions - eventId,
            reactionCounts = cur.reactionCounts + (eventId to maxOf(0, (cur.reactionCounts[eventId] ?: 0) - 1)),
            likeReactionCounts = cur.likeReactionCounts + (
                eventId to maxOf(0, (cur.likeReactionCounts[eventId] ?: 0) - 1)
                ),
            reactionPubkeys = if (eventId == this.eventId && ownPubkey != null) {
                cur.reactionPubkeys - ownPubkey!!
            } else {
                cur.reactionPubkeys
            },
            rootReactionsByPubkey = if (eventId == this.eventId && ownPubkey != null) {
                cur.rootReactionsByPubkey - ownPubkey!!
            } else {
                cur.rootReactionsByPubkey
            },
        )
        if (reactionEventId.isEmpty()) return
        launch {
            val signer = accountSession?.signer ?: return@launch
            runCatching {
                val deletion = signer.sign(
                    content = "",
                    kind = 5,
                    tags = listOf(listOf("e", reactionEventId)),
                )
                NostrRepository.publish(deletion)
            }
        }
    }

    fun reactWithEmoji(eventId: String, eventPubkey: String, option: ReactionOption) {
        val cur = _state.value
        if (
            cur.likedReactions.containsKey(eventId) ||
            cur.ownEmojiReactionEventIds[eventId].orEmpty().isNotEmpty()
        ) return
        _state.value = cur.withOptimisticEmojiReaction(eventId, option, "")
        launch {
            val signer = accountSession?.signer ?: run {
                _state.value = _state.value.withoutOptimisticEmojiReaction(eventId, option)
                return@launch
            }
            runCatching {
                signer.sign(
                    content = option.eventContent,
                    kind = 7,
                    tags = option.eventTags(eventId, eventPubkey),
                ).also { reaction ->
                    seenReactionIds.add(reaction.id)
                    NostrRepository.publish(reaction)
                }
            }.onSuccess { reaction ->
                val state = _state.value
                _state.value = state.copy(
                    ownEmojiReactionEventIds = state.ownEmojiReactionEventIds + (
                        eventId to state.ownEmojiReactionEventIds[eventId].orEmpty()
                            .plus(option.key to reaction.id)
                        ),
                )
            }.onFailure {
                _state.value = _state.value.withoutOptimisticEmojiReaction(eventId, option)
            }
        }
    }

    fun unreactWithEmoji(eventId: String, option: ReactionOption) {
        val cur = _state.value
        val reactionEventId = cur.ownEmojiReactionEventIds[eventId]?.get(option.key) ?: return
        _state.value = cur.withoutOptimisticEmojiReaction(eventId, option)
        if (reactionEventId.isEmpty()) return
        launch {
            val signer = accountSession?.signer ?: return@launch
            runCatching {
                val deletion = signer.sign(
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
            repostCount = cur.repostCount + 1,
            ownRepostEventId = "",
        )
        launch {
            val signer = accountSession?.signer ?: return@launch
            runCatching {
                val repostEvent = signer.sign(
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
            repostCount = maxOf(0, cur.repostCount - 1),
            ownRepostEventId = null,
        )
        if (repostEventId.isEmpty()) return
        launch {
            val signer = accountSession?.signer ?: return@launch
            runCatching {
                val deletion = signer.sign(
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
            NostrRepository.subscribe(rootSubId, NostrFilter(ids = listOf(eventId), kinds = listOf(noteContext.eventKind), limit = 1))
            NostrRepository.subscribe(repliesSubId, NostrFilter(kinds = listOf(noteContext.eventKind), eTags = listOf(eventId), limit = 100))
            NostrRepository.subscribe(repostSubId, NostrFilter(kinds = listOf(6), eTags = listOf(eventId), limit = 500))
            NostrRepository.subscribe(
                quoteRepostSubId,
                NostrFilter(kinds = listOf(noteContext.eventKind), qTags = listOf(eventId), limit = 500),
            )
        }

        subscriptionJobs += launch {
            NostrRepository.events(rootSubId).collect { event ->
                if (!noteContext.matches(event) || event.id != eventId) return@collect
                _state.value = _state.value.copy(root = event, isLoading = false)
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
                val replyParentId = noteContext.replyTargetId(event)
                replyParentId?.let { parentId ->
                    NostrRepository.subscribe(
                        replyParentSubId,
                        NostrFilter(ids = listOf(parentId), kinds = listOf(noteContext.eventKind), limit = 1),
                    )
                }
                scheduleQuotedEventFetch(quotedEventIds(event).filter { it != replyParentId })
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(replyParentSubId).collect { event ->
                if (!noteContext.matches(event)) return@collect
                val cur = _state.value
                if (cur.quotedEvents.containsKey(event.id)) return@collect
                _state.value = cur.copy(quotedEvents = cur.quotedEvents + (event.id to event))
                scheduleProfileFetch(event.pubkey)
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(quotedEventSubId).collect { event ->
                if (!noteContext.matches(event)) return@collect
                pendingQuotedEventIds.remove(event.id)
                val cur = _state.value
                if (cur.quotedEvents.containsKey(event.id)) return@collect
                _state.value = cur.copy(quotedEvents = cur.quotedEvents + (event.id to event))
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(repliesSubId).collect { event ->
                if (!noteContext.matches(event) || !seenReplyIds.add(event.id)) return@collect
                val targetId = noteContext.replyTargetId(event) ?: return@collect
                if (targetId != eventId) {
                    scheduleReplyCountFetch(targetId)
                    return@collect
                }
                val cur = _state.value
                _state.value = cur.copy(
                    replies = (cur.replies + event).sortedBy { it.createdAt },
                    isLoading = false,
                )
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
                val replyParentId = noteContext.replyTargetId(event)
                scheduleQuotedEventFetch(quotedEventIds(event).filter { it != replyParentId })
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(replyCountSubId).collect { event ->
                if (!noteContext.matches(event) || !seenReplyCountIds.add(event.id)) return@collect
                val targetId = noteContext.replyTargetId(event) ?: return@collect
                val cur = _state.value
                val targetReplies = cur.repliesByEventId[targetId].orEmpty()
                val updatedTargetReplies = if (targetReplies.any { it.id == event.id }) {
                    targetReplies
                } else {
                    (targetReplies + event).sortedBy { it.createdAt }
                }
                _state.value = cur.copy(
                    replyCounts = cur.replyCounts + (targetId to (cur.replyCounts[targetId] ?: 0) + 1),
                    repliesByEventId = cur.repliesByEventId + (targetId to updatedTargetReplies),
                )
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(reactionSubId).collect { event ->
                if (event.kind != 7 || !seenReactionIds.add(event.id)) return@collect
                rememberReceivedEvent(receivedReactionEvents, event)
                val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                    ?: return@collect
                if (targetId !in watchedReactionEventIds) return@collect
                val cur = _state.value
                val isOwn = ownPubkey != null && event.pubkey == ownPubkey
                val customReaction = event.toCustomReaction()
                val unicodeReaction = event.toUnicodeReaction()
                val reactionOption = event.toReactionOption()
                val rootReactionPubkeys = if (
                    targetId == eventId &&
                    event.pubkey !in cur.reactionPubkeys
                ) {
                    cur.reactionPubkeys + event.pubkey
                } else {
                    cur.reactionPubkeys
                }
                val rootReactionsByPubkey = if (targetId == eventId) {
                    val previous = cur.rootReactionsByPubkey[event.pubkey]
                    if (
                        previous == null ||
                        event.createdAt > previous.createdAt ||
                        (event.createdAt == previous.createdAt && event.id > previous.id)
                    ) {
                        cur.rootReactionsByPubkey + (event.pubkey to event)
                    } else {
                        cur.rootReactionsByPubkey
                    }
                } else {
                    cur.rootReactionsByPubkey
                }
                _state.value = cur.copy(
                    reactionCounts = cur.reactionCounts + (targetId to (cur.reactionCounts[targetId] ?: 0) + 1),
                    likeReactionCounts = if (event.content.trim() == "+") {
                        cur.likeReactionCounts + (
                            targetId to (cur.likeReactionCounts[targetId] ?: 0) + 1
                            )
                    } else {
                        cur.likeReactionCounts
                    },
                    customReactions = if (customReaction != null) {
                        cur.customReactions + (
                            targetId to cur.customReactions[targetId]
                                .orEmpty()
                                .incrementedWith(customReaction)
                        )
                    } else {
                        cur.customReactions
                    },
                    unicodeReactions = if (unicodeReaction != null) {
                        cur.unicodeReactions + (
                            targetId to cur.unicodeReactions[targetId]
                                .orEmpty()
                                .incrementedWithUnicodeReaction(unicodeReaction)
                        )
                    } else {
                        cur.unicodeReactions
                    },
                    reactionPubkeys = rootReactionPubkeys,
                    rootReactionsByPubkey = rootReactionsByPubkey,
                    likedReactions = if (
                        isOwn &&
                        event.content.trim() == "+" &&
                        !cur.likedReactions.containsKey(targetId)
                    ) {
                        cur.likedReactions + (targetId to event.id)
                    } else {
                        cur.likedReactions
                    },
                    ownEmojiReactionEventIds = if (isOwn && reactionOption != null) {
                        cur.ownEmojiReactionEventIds + (
                            targetId to cur.ownEmojiReactionEventIds[targetId].orEmpty()
                                .plus(reactionOption.key to event.id)
                            )
                    } else {
                        cur.ownEmojiReactionEventIds
                    },
                )
                scheduleProfileFetch(event.pubkey)
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(repostSubId).collect { event ->
                if (event.kind != 6 || !seenRepostIds.add(event.id)) return@collect
                rememberReceivedEvent(receivedRepostEvents, event)
                if (event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1) != eventId) return@collect
                val cur = _state.value
                val ownRepostEventId = if (ownPubkey != null && event.pubkey == ownPubkey) {
                    event.id
                } else {
                    cur.ownRepostEventId
                }
                _state.value = cur.copy(
                    repostPubkeys = if (event.pubkey in cur.repostPubkeys) {
                        cur.repostPubkeys
                    } else {
                        cur.repostPubkeys + event.pubkey
                    },
                    repostCount = cur.repostCount + 1,
                    ownRepostEventId = ownRepostEventId,
                )
                scheduleProfileFetch(event.pubkey)
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(quoteRepostSubId).collect { event ->
                if (!noteContext.matches(event) || !seenQuoteRepostIds.add(event.id)) return@collect
                if (event.tags.none { it.firstOrNull() == "q" && it.getOrNull(1) == eventId }) return@collect
                val cur = _state.value
                _state.value = cur.copy(
                    quoteReposts = (cur.quoteReposts + event)
                        .distinctBy { it.id }
                        .sortedByDescending { it.createdAt },
                    repostCount = cur.repostCount + 1,
                )
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
                scheduleReplyCountFetch(event.id)
                scheduleReactionFetch(event.id)
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
        replyCountBatchJob?.cancel()
        reactionBatchJob?.cancel()
        requestedProfilePubkeys.clear()
        pendingQuotedEventIds.clear()
        NostrRepository.close(rootSubId)
        NostrRepository.close(repliesSubId)
        NostrRepository.close(replyCountSubId)
        NostrRepository.close(reactionSubId)
        NostrRepository.close(repostSubId)
        NostrRepository.close(quoteRepostSubId)
        NostrRepository.close(replyParentSubId)
        NostrRepository.close(quotedEventSubId)
    }

    private fun scheduleQuotedEventFetch(eventIds: List<String>) {
        val missingIds = eventIds.filter { id ->
            id !in _state.value.quotedEvents && pendingQuotedEventIds.add(id)
        }
        if (missingIds.isEmpty()) return
        launch {
            NostrRepository.subscribe(
                quotedEventSubId,
                NostrFilter(
                    ids = pendingQuotedEventIds.toList(),
                    kinds = listOf(noteContext.eventKind),
                ),
            )
        }
    }

    private fun rememberReceivedEvent(events: LinkedHashMap<String, NostrEvent>, event: NostrEvent) {
        events[event.id] = event
        while (events.size > MAX_RECEIVED_ENGAGEMENT_EVENTS) events.remove(events.keys.first())
    }

    private fun reconcileOwnEngagement() {
        val pubkey = ownPubkey ?: return
        val current = _state.value
        var likedReactions = current.likedReactions
        var ownEmojiReactionEventIds = current.ownEmojiReactionEventIds
        receivedReactionEvents.values.filter { it.pubkey == pubkey }.forEach { event ->
            val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                ?: return@forEach
            if (event.content.trim() == "+") likedReactions = likedReactions + (targetId to event.id)
            event.toReactionOption()?.let { option ->
                ownEmojiReactionEventIds = ownEmojiReactionEventIds + (
                    targetId to ownEmojiReactionEventIds[targetId].orEmpty().plus(option.key to event.id)
                )
            }
        }
        val ownRepostEventId = receivedRepostEvents.values
            .lastOrNull { event ->
                event.pubkey == pubkey &&
                    event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1) == eventId
            }
            ?.id
            ?: current.ownRepostEventId
        _state.value = current.copy(
            likedReactions = likedReactions,
            ownEmojiReactionEventIds = ownEmojiReactionEventIds,
            ownRepostEventId = ownRepostEventId,
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopSubscriptions()
    }

    private fun scheduleProfileFetch(pubkey: String) {
        requestedProfilePubkeys.add(pubkey)
        ProfileRepository.getCached(pubkey)?.let { cachedProfile ->
            if (_state.value.profiles[pubkey] != cachedProfile) {
                _state.value = _state.value.copy(
                    profiles = _state.value.profiles + (pubkey to cachedProfile),
                )
            }
        }
        launch {
            ProfileRepository.ensureProfiles(
                pubkeys = setOf(pubkey),
                policy = ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS),
            )
        }
    }

    private fun scheduleMentionedProfileFetch(text: String) {
        extractNpubReferences(text).forEach { reference ->
            scheduleProfileFetch(reference.pubkey)
        }
    }

    private fun rememberPublishedReply(event: NostrEvent) {
        if (!seenReplyIds.add(event.id)) return
        seenReplyCountIds.add(event.id)
        val cur = _state.value
        val updatedReplies = if (cur.replies.any { it.id == event.id }) {
            cur.replies
        } else {
            (cur.replies + event).sortedBy { it.createdAt }
        }
        _state.value = cur.copy(
            replies = updatedReplies,
            replyCounts = cur.replyCounts + (eventId to (cur.replyCounts[eventId] ?: cur.replies.size) + 1),
            isLoading = false,
        )
        scheduleProfileFetch(event.pubkey)
        scheduleMentionedProfileFetch(event.content)
        scheduleReplyCountFetch(event.id)
        scheduleReactionFetch(event.id)
    }

    private fun scheduleReplyCountFetch(eventId: String) {
        if (!watchedEventIds.add(eventId)) return
        while (watchedEventIds.size > MAX_WATCHED_EVENTS) watchedEventIds.remove(watchedEventIds.first())
        replyCountBatchJob?.cancel()
        replyCountBatchJob = launch {
            delay(300)
            NostrRepository.subscribe(
                replyCountSubId,
                NostrFilter(kinds = listOf(noteContext.eventKind), eTags = watchedEventIds.toList()),
            )
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
        private const val MAX_RECEIVED_ENGAGEMENT_EVENTS = 2_000
        private const val MAX_WATCHED_EVENTS = 100
        private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
    }
}

private fun ThreadViewModel.UiState.withoutOptimisticLike(
    eventId: String,
    ownPubkey: String?,
): ThreadViewModel.UiState {
    if (likedReactions[eventId] != "") return this
    return copy(
        likedReactions = likedReactions - eventId,
        reactionCounts = reactionCounts + (
            eventId to maxOf(0, (reactionCounts[eventId] ?: 0) - 1)
            ),
        likeReactionCounts = likeReactionCounts + (
            eventId to maxOf(0, (likeReactionCounts[eventId] ?: 0) - 1)
            ),
        reactionPubkeys = if (eventId == root?.id && ownPubkey != null) {
            reactionPubkeys - ownPubkey
        } else {
            reactionPubkeys
        },
    )
}

private fun ThreadViewModel.UiState.withOptimisticEmojiReaction(
    eventId: String,
    option: ReactionOption,
    reactionEventId: String,
): ThreadViewModel.UiState = copy(
    reactionCounts = reactionCounts + (eventId to (reactionCounts[eventId] ?: 0) + 1),
    customReactions = if (option is ReactionOption.Custom) {
        customReactions + (
            eventId to customReactions[eventId].orEmpty().incrementedWith(
                CustomReaction(option.shortcode.trim().trim(':'), option.imageUrl.trim()),
            )
            )
    } else {
        customReactions
    },
    unicodeReactions = if (option is ReactionOption.Unicode) {
        unicodeReactions + (
            eventId to unicodeReactions[eventId].orEmpty().incrementedWithUnicodeReaction(
                UnicodeReaction(option.value.trim()),
            )
            )
    } else {
        unicodeReactions
    },
    ownEmojiReactionEventIds = ownEmojiReactionEventIds + (
        eventId to ownEmojiReactionEventIds[eventId].orEmpty()
            .plus(option.key to reactionEventId)
        ),
)

private fun ThreadViewModel.UiState.withoutOptimisticEmojiReaction(
    eventId: String,
    option: ReactionOption,
): ThreadViewModel.UiState {
    val remaining = ownEmojiReactionEventIds[eventId].orEmpty() - option.key
    return copy(
        reactionCounts = reactionCounts + (
            eventId to maxOf(0, (reactionCounts[eventId] ?: 0) - 1)
            ),
        customReactions = if (option is ReactionOption.Custom) {
            customReactions + (
                eventId to customReactions[eventId].orEmpty().decrementedWith(option)
                )
        } else {
            customReactions
        },
        unicodeReactions = if (option is ReactionOption.Unicode) {
            unicodeReactions + (
                eventId to unicodeReactions[eventId].orEmpty()
                    .decrementedWithUnicodeReaction(option)
                )
        } else {
            unicodeReactions
        },
        ownEmojiReactionEventIds = if (remaining.isEmpty()) {
            ownEmojiReactionEventIds - eventId
        } else {
            ownEmojiReactionEventIds + (eventId to remaining)
        },
    )
}
