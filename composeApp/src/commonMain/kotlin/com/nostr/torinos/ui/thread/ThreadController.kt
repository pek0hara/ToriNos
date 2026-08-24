package com.nostr.torinos.ui.thread

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.engagement.EngagementAction
import com.nostr.torinos.engagement.EngagementOperationId
import com.nostr.torinos.engagement.EngagementReducer
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.NoteEngagementCommand
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.engagement.NoteTarget
import com.nostr.torinos.engagement.PendingEngagementOperation
import com.nostr.torinos.engagement.displayOwnEmojiReactionEventIds
import com.nostr.torinos.engagement.hasOwnReaction
import com.nostr.torinos.engagement.isRepostedByMe
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.incrementedWithUnicodeReaction
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toUnicodeReaction
import com.nostr.torinos.model.toReactionOption
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.ui.timeline.ProfileHydrator
import com.nostr.torinos.ui.timeline.NoteEngagementCoordinator
import com.nostr.torinos.ui.timeline.QuoteResolver
import com.nostr.torinos.ui.thread.ThreadViewModel.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ThreadController(
    private val eventId: String,
    private val noteContext: NoteContext = NoteContext.Timeline,
    private val accountSession: AccountSession? = null,
    private val scope: CoroutineScope,
    private val autoStart: Boolean = true,
) {

    private fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    private val store = ThreadStore()
    private val _state = store
    val state: kotlinx.coroutines.flow.StateFlow<UiState> = store.state

    private val sessionKey = accountSession?.sessionId?.hashCode()?.toString() ?: "anonymous"
    private val shortId = "${eventId.take(16)}-$sessionKey"
    private val rootSubId = "thread-root-$shortId"
    private val repliesSubId = "thread-replies-$shortId"
    private val replyCountSubId = "thread-count-$shortId"
    private val reactionSubId = "thread-react-$shortId"
    private val repostSubId = "thread-repost-$shortId"
    private val quoteRepostSubId = "thread-qrepost-$shortId"
    private val replyParentSubId = "thread-parent-$shortId"

    private val subscriptionJobs = mutableListOf<Job>()
    private val lifecycleJobs = mutableListOf<Job>()
    private val seenReplyIds = linkedSetOf<String>()
    private val seenReplyCountIds = linkedSetOf<String>()
    private val seenReactionIds = linkedSetOf<String>()
    private val seenRepostIds = linkedSetOf<String>()
    private val seenQuoteRepostIds = linkedSetOf<String>()
    private val receivedReactionEvents = linkedMapOf<String, NostrEvent>()
    private val receivedRepostEvents = linkedMapOf<String, NostrEvent>()
    private val watchedEventIds = linkedSetOf<String>()
    private val watchedReactionEventIds = linkedSetOf<String>()
    private val pendingQuotedEventIds = linkedSetOf<String>()
    private var replyCountBatchJob: Job? = null
    private var reactionBatchJob: Job? = null
    private var started = false
    private var ownPubkey: String? = null
    private val engagementCoordinator = NoteEngagementCoordinator(accountSession?.signer)
    private val replyPublisher = ReplyPublisher(accountSession?.signer)
    private val quoteResolver = QuoteResolver("thread-quote-$shortId")
    private val profileHydrator = ProfileHydrator(
        scope,
        ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS),
    )
    private var nextEngagementOperationId = 0L

    init {
        if (isWriteSupported) {
            launch {
                ownPubkey = accountSession?.pubkey
                reconcileOwnEngagement()
            }
        }
        lifecycleJobs += launch {
            profileHydrator.updates.collect { patch ->
                _state.value = _state.value.copy(profiles = _state.value.profiles + patch.profiles)
            }
        }
        if (autoStart) startSubscriptions()
    }

    fun onReplyTextChange(text: String) {
        _state.value = _state.value.copy(replyText = text, replyError = null)
    }

    fun consumeEngagementError() {
        _state.value = _state.value.copy(engagementError = null)
    }

    fun submitReply() {
        val root = _state.value.root ?: return
        val text = _state.value.replyText.trim()
        if (text.isBlank()) return
        _state.value = _state.value.copy(isReplying = true, replyError = null)
        launch {
            when (
                val result = replyPublisher.publish(
                    ReplyCommand(
                        content = text,
                        replyToId = root.id,
                        replyToPubkey = root.pubkey,
                        noteContext = noteContext,
                    ),
                )
            ) {
                is ReplyPublishResult.Published -> {
                    rememberPublishedReply(result.event)
                    _state.value = _state.value.copy(isReplying = false, replyText = "")
                }
                ReplyPublishResult.Failure.EmptyContent -> {
                    _state.value = _state.value.copy(isReplying = false)
                }
                ReplyPublishResult.Failure.MissingSigner -> {
                    _state.value = _state.value.copy(
                        isReplying = false,
                        replyError = "秘密鍵が設定されていません",
                    )
                }
                is ReplyPublishResult.Failure.PublishFailed -> {
                    _state.value = _state.value.copy(
                        isReplying = false,
                        replyError = result.cause.message ?: "返信に失敗しました",
                    )
                }
            }
        }
    }

    fun react(eventId: String, eventPubkey: String) {
        runEngagementOperation(
            eventId,
            EngagementRequest.AddLike,
            NoteEngagementCommand.AddLike(NoteTarget(eventId, eventPubkey)),
            "リアクションの送信に失敗しました",
        )
    }

    fun unreact(eventId: String) {
        val reactionEventId = _state.value.likedReactions[eventId] ?: return
        runEngagementOperation(
            eventId,
            EngagementRequest.RemoveLike,
            NoteEngagementCommand.RemoveReaction(reactionEventId),
            "リアクションの解除に失敗しました",
        )
    }

    fun reactWithEmoji(eventId: String, eventPubkey: String, option: ReactionOption) {
        runEngagementOperation(
            eventId,
            EngagementRequest.AddEmoji(option),
            NoteEngagementCommand.AddEmoji(NoteTarget(eventId, eventPubkey), option),
            "リアクションの送信に失敗しました",
        )
    }

    fun unreactWithEmoji(eventId: String, option: ReactionOption) {
        val reactionEventId = _state.value.ownEmojiReactionEventIds[eventId]?.get(option.key) ?: return
        runEngagementOperation(
            eventId,
            EngagementRequest.RemoveEmoji(option),
            NoteEngagementCommand.RemoveReaction(reactionEventId),
            "リアクションの解除に失敗しました",
        )
    }

    fun repost(event: NostrEvent) {
        runEngagementOperation(
            event.id,
            EngagementRequest.AddRepost,
            NoteEngagementCommand.AddRepost(event),
            "リポストの送信に失敗しました",
        )
    }

    fun unrepost() {
        val repostEventId = _state.value.ownRepostEventId ?: return
        runEngagementOperation(
            eventId,
            EngagementRequest.RemoveRepost,
            NoteEngagementCommand.RemoveRepost(repostEventId),
            "リポストの解除に失敗しました",
        )
    }

    private fun runEngagementOperation(
        targetEventId: String,
        request: EngagementRequest,
        command: NoteEngagementCommand,
        failureMessage: String,
    ) {
        val operationId = EngagementOperationId("thread-${++nextEngagementOperationId}")
        val before = _state.value.noteEngagement(targetEventId, eventId)
        val optimistic = engagementCoordinator.begin(before, operationId, request)
        if (optimistic == before) return
        _state.value = _state.value.withEngagement(targetEventId, eventId, optimistic, ownPubkey)
            .copy(engagementError = null)
        launch {
            var committed = false
            var failure: Throwable? = null
            var signedEvent: NostrEvent? = null
            try {
                val published = engagementCoordinator.execute(command) { signed ->
                    signedEvent = signed
                    when (command) {
                        is NoteEngagementCommand.AddLike,
                        is NoteEngagementCommand.AddEmoji,
                        -> seenReactionIds.add(signed.id)
                        is NoteEngagementCommand.AddRepost -> seenRepostIds.add(signed.id)
                        is NoteEngagementCommand.RemoveReaction,
                        is NoteEngagementCommand.RemoveRepost,
                        -> Unit
                    }
                }.getOrThrow()
                val current = _state.value.noteEngagement(targetEventId, eventId)
                var next = _state.value.withEngagement(
                    targetEventId,
                    eventId,
                    engagementCoordinator.commit(current, operationId, published.id),
                    ownPubkey,
                )
                if (targetEventId == eventId && command is NoteEngagementCommand.AddLike) {
                    signedEvent?.let { reaction ->
                        next = next.copy(
                            rootReactionsByPubkey = next.rootReactionsByPubkey + (reaction.pubkey to reaction),
                        )
                    }
                }
                if (targetEventId == eventId && command is NoteEngagementCommand.RemoveReaction && request is EngagementRequest.RemoveLike) {
                    ownPubkey?.let { next = next.copy(rootReactionsByPubkey = next.rootReactionsByPubkey - it) }
                }
                _state.value = next
                committed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            } finally {
                if (!committed) {
                    val current = _state.value.noteEngagement(targetEventId, eventId)
                    _state.value = _state.value.withEngagement(
                        targetEventId,
                        eventId,
                        engagementCoordinator.rollback(current, operationId),
                        ownPubkey,
                    )
                }
            }
            if (failure != null) _state.value = _state.value.copy(engagementError = failureMessage)
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
                val tree = ThreadTreeReducer.reduce(
                    _state.value.threadTreeState(),
                    ThreadTreeAction.RootReceived(event),
                )
                _state.value = _state.value.withThreadTree(tree).copy(isLoading = false)
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
            NostrRepository.events(repliesSubId).collect { event ->
                if (!noteContext.matches(event) || !seenReplyIds.add(event.id)) return@collect
                val targetId = noteContext.replyTargetId(event) ?: return@collect
                if (targetId != eventId) {
                    scheduleReplyCountFetch(targetId)
                    return@collect
                }
                val tree = ThreadTreeReducer.reduce(
                    _state.value.threadTreeState(),
                    ThreadTreeAction.DirectReplyReceived(event),
                )
                _state.value = _state.value.withThreadTree(tree).copy(isLoading = false)
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
                val tree = ThreadTreeReducer.reduce(
                    _state.value.threadTreeState(),
                    ThreadTreeAction.DescendantReplyReceived(targetId, event),
                )
                _state.value = _state.value.withThreadTree(tree)
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
        pendingQuotedEventIds.clear()
        NostrRepository.close(rootSubId)
        NostrRepository.close(repliesSubId)
        NostrRepository.close(replyCountSubId)
        NostrRepository.close(reactionSubId)
        NostrRepository.close(repostSubId)
        NostrRepository.close(quoteRepostSubId)
        NostrRepository.close(replyParentSubId)
    }

    private fun scheduleQuotedEventFetch(eventIds: List<String>) {
        val missingIds = eventIds.filter { id ->
            id !in _state.value.quotedEvents && pendingQuotedEventIds.add(id)
        }
        if (missingIds.isEmpty()) return
        launch {
            try {
                val resolution = quoteResolver.resolve(
                    eventIds = missingIds.toSet(),
                    kinds = listOf(noteContext.eventKind),
                )
                if (resolution.events.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        quotedEvents = _state.value.quotedEvents + resolution.events,
                    )
                    resolution.events.values.forEach { event ->
                        scheduleProfileFetch(event.pubkey)
                        scheduleMentionedProfileFetch(event.content)
                    }
                }
            } finally {
                pendingQuotedEventIds.removeAll(missingIds.toSet())
            }
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

    fun close() {
        stopSubscriptions()
        lifecycleJobs.forEach { it.cancel() }
        lifecycleJobs.clear()
        profileHydrator.close()
    }

    private fun scheduleProfileFetch(pubkey: String) {
        profileHydrator.request(setOf(pubkey))
    }

    private fun scheduleMentionedProfileFetch(text: String) {
        profileHydrator.requestMentioned(text)
    }

    private fun rememberPublishedReply(event: NostrEvent) {
        if (!seenReplyIds.add(event.id)) return
        seenReplyCountIds.add(event.id)
        val tree = ThreadTreeReducer.reduce(
            _state.value.threadTreeState(),
            ThreadTreeAction.ReplyPublished(eventId, event),
        )
        _state.value = _state.value.withThreadTree(tree).copy(isLoading = false)
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

internal fun ThreadViewModel.UiState.noteEngagement(
    eventId: String,
    rootEventId: String?,
): NoteEngagementState = NoteEngagementState(
    reactionCount = reactionCounts[eventId] ?: 0,
    likeReactionCount = likeReactionCounts[eventId] ?: 0,
    customReactions = customReactions[eventId].orEmpty(),
    unicodeReactions = unicodeReactions[eventId].orEmpty(),
    ownLikeEventId = likedReactions[eventId],
    ownEmojiReactionEventIds = ownEmojiReactionEventIds[eventId].orEmpty(),
    repostCount = if (eventId == rootEventId) repostCount else 0,
    ownRepostEventId = if (eventId == rootEventId) ownRepostEventId else null,
    pendingOperations = pendingEngagementOperations[eventId].orEmpty(),
)

private fun ThreadViewModel.UiState.withEngagement(
    eventId: String,
    rootEventId: String,
    engagement: NoteEngagementState,
    ownPubkey: String?,
): ThreadViewModel.UiState {
    val isRoot = eventId == rootEventId
    return copy(
        reactionCounts = reactionCounts + (eventId to engagement.reactionCount),
        likeReactionCounts = likeReactionCounts + (eventId to engagement.likeReactionCount),
    customReactions = customReactions.putListOrRemove(eventId, engagement.customReactions),
    unicodeReactions = unicodeReactions.putListOrRemove(eventId, engagement.unicodeReactions),
        likedReactions = likedReactions.putOrRemove(eventId, engagement.ownLikeEventId),
    ownEmojiReactionEventIds = ownEmojiReactionEventIds.putMapOrRemove(
            eventId,
            engagement.ownEmojiReactionEventIds,
        ),
        repostCount = if (isRoot) engagement.repostCount else repostCount,
        ownRepostEventId = if (isRoot) engagement.ownRepostEventId else ownRepostEventId,
    pendingEngagementOperations = pendingEngagementOperations.putMapOrRemove(
            eventId,
            engagement.pendingOperations,
        ),
        reactionPubkeys = if (isRoot && ownPubkey != null) {
            if (engagement.hasOwnReaction) reactionPubkeys + ownPubkey else reactionPubkeys - ownPubkey
        } else {
            reactionPubkeys
        }.distinct(),
        repostPubkeys = if (isRoot && ownPubkey != null) {
            if (engagement.isRepostedByMe) repostPubkeys + ownPubkey else repostPubkeys - ownPubkey
        } else {
            repostPubkeys
        }.distinct(),
    )
}

private fun <K, V> Map<K, V>.putOrRemove(key: K, value: V?): Map<K, V> =
    if (value == null) this - key else this + (key to value)

private fun <K, V> Map<K, List<V>>.putListOrRemove(key: K, value: List<V>): Map<K, List<V>> =
    if (value.isEmpty()) this - key else this + (key to value)

private fun <K, K2, V2> Map<K, Map<K2, V2>>.putMapOrRemove(
    key: K,
    value: Map<K2, V2>,
): Map<K, Map<K2, V2>> = if (value.isEmpty()) this - key else this + (key to value)
