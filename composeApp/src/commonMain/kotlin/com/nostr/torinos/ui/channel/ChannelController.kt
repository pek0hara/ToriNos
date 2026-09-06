package com.nostr.torinos.ui.channel

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.ui.channel.ChannelViewModel.EditThreadDialogState
import com.nostr.torinos.ui.channel.ChannelViewModel.UiState
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
import com.nostr.torinos.engagement.isRepostedByMe
import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.incrementedWithUnicodeReaction
import com.nostr.torinos.model.toChannelMeta
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toUnicodeReaction
import com.nostr.torinos.model.toReactionOption
import com.nostr.torinos.network.ChannelReadingPosition
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.network.ChannelCacheStore
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.SafeCoroutineLauncher
import com.nostr.torinos.ui.timeline.NoteEngagementCoordinator
import com.nostr.torinos.ui.timeline.StateStore
import com.nostr.torinos.ui.timeline.SignedEventPublisher
import com.nostr.torinos.ui.timeline.SignedPublishResult
import kotlin.time.Clock
import com.nostr.torinos.util.logException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ChannelController(
    private val channelId: String,
    private val relayUrl: String? = null,
    private val accountSession: AccountSession? = null,
    private val scope: CoroutineScope,
    private val autoStart: Boolean = true,
) {
    private val safeCoroutineLauncher = SafeCoroutineLauncher(scope, "ChannelController")
    private fun launch(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = safeCoroutineLauncher.launch(start = start, block = block)

    private val _state = StateStore<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.state

    private val sessionKey = accountSession?.sessionId?.hashCode()?.toString() ?: "anonymous"
    private val shortId = "${channelId.take(16)}-$sessionKey"
    private val relayKey = relayUrl?.hashCode()?.toString() ?: "all"
    private val metaSubId = "ch-meta-$shortId-$relayKey"
    private val metaUpdateSubId = "ch-meta-update-$shortId-$relayKey"
    private val msgSubId = "ch-msg-$shortId-$relayKey"
    private val histSubId = "ch-hist-$shortId-$relayKey"
    private val replyCountSubId = "ch-reply-count-$shortId-$relayKey"
    private val reactionSubId = "ch-react-$shortId-$relayKey"
    private val repostSubId = "ch-repost-$shortId-$relayKey"
    private val quoteRepostSubId = "ch-qrepost-$shortId-$relayKey"

    private val seenReplyIds = linkedSetOf<String>()
    private val seenReactionIds = linkedSetOf<String>()
    private val seenRepostIds = linkedSetOf<String>()
    private val seenQuoteRepostIds = linkedSetOf<String>()
    private val receivedReactionEvents = linkedMapOf<String, NostrEvent>()
    private val receivedRepostEvents = linkedMapOf<String, NostrEvent>()
    private val watchedEventIds = linkedSetOf<String>()
    private val pendingPubkeys = mutableSetOf<String>()
    private var profileBatchJob: Job? = null
    private var engagementBatchJob: Job? = null
    private val jobs = mutableListOf<Job>()
    private val lifecycleJobs = mutableListOf<Job>()

    private var requestSequence = 0L
    private var lastMarkedReadAt = -1L
    private var positionSaveJob: Job? = null
    private var pendingReadingPosition: ChannelReadingPosition? = null
    private var savedReadingPosition: ChannelReadingPosition? = null
    private var currentChannelMeta = ChannelMeta()
    private var currentChannelOwnerPubkey: String? = null
    private var latestMetaUpdateCreatedAt = -1L
    private var currentMessages = emptyList<NostrEvent>()
    private var currentProfiles = emptyMap<String, NostrProfile>()
    private var currentReplyCounts = emptyMap<String, Int>()
    private var currentReactionCounts = emptyMap<String, Int>()
    private var currentLikeReactionCounts = emptyMap<String, Int>()
    private var currentCustomReactions = emptyMap<String, List<CustomReaction>>()
    private var currentUnicodeReactions = emptyMap<String, List<UnicodeReaction>>()
    private var currentReactionEvents = emptyMap<String, List<NostrEvent>>()
    private var currentRepostCounts = emptyMap<String, Int>()
    private var currentRepostPubkeys = emptyMap<String, List<String>>()
    private var currentLikedReactions = emptyMap<String, String>()
    private var currentOwnEmojiReactionEventIds = emptyMap<String, Map<String, String>>()
    private var currentRepostedEvents = emptyMap<String, String>()
    private var currentPendingEngagementOperations = emptyMap<String, Map<EngagementSlot, PendingEngagementOperation>>()
    private var ownPubkey: String? = null
    private val engagementCoordinator = NoteEngagementCoordinator(accountSession?.signer)
    private val signedEventPublisher = SignedEventPublisher(accountSession?.signer)
    private var nextEngagementOperationId = 0L
    private val noteContext = NoteContext.Channel(channelId)
    private val openedAt = Clock.System.now().epochSeconds

    private val history = ChannelHistory(
        scope = scope,
        channelId = channelId,
        fetch = ::fetchHistoryPage,
        lookup = { id ->
            ChannelCacheStore.getMessage(channelId, id)
                ?: fetchHistoryPage(NostrFilter(ids = listOf(id), kinds = listOf(42), limit = 1))
                    .events.firstOrNull()
        },
    )

    private suspend fun fetchHistoryPage(filter: NostrFilter): ChannelHistoryPage {
        val events = mutableListOf<NostrEvent>()
        val complete = fetchChannelEvents(
            SubscriptionSpec(
                id = "$histSubId-${requestSequence++}",
                filters = listOf(filter),
                target = relayUrl?.let(RelayTarget::Single) ?: RelayTarget.AllEnabled,
                behavior = SubscriptionBehavior.Fetch(10_000),
            ),
        ) { event -> if (noteContext.matches(event)) events.add(event) }
        try {
            relayUrl?.let { url -> events.forEach { ChannelCacheStore.upsertMessage(url, it, channelId) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logException("ChannelController", error, "Could not cache messages")
        }
        return ChannelHistoryPage(events.distinctBy { it.id }, complete)
    }

    init {
        lifecycleJobs += launch {
            ownPubkey = accountSession?.pubkey
            reconcileOwnEngagement()
        }
        if (autoStart) start()
    }

    fun onDraftChange(text: String) {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(draftText = text, postError = null)
    }

    fun consumeEngagementError() {
        val ready = _state.value as? UiState.Ready ?: return
        _state.value = ready.copy(engagementError = null)
    }

    fun sendMessage() {
        val current = _state.value as? UiState.Ready ?: return
        val text = current.draftText.trim()
        if (text.isBlank() || current.isPosting) return

        _state.value = current.copy(isPosting = true, postError = null)
        launch {
            val result = signedEventPublisher.publish(
                text,
                noteContext.eventKind,
                noteContext.replyTags(replyToId = null, replyToPubkey = null) +
                    listOf(listOf("client", "ToriNos")),
            )
            (_state.value as? UiState.Ready)?.let { ready ->
                _state.value = when (result) {
                    is SignedPublishResult.Published -> ready.copy(draftText = "", isPosting = false)
                    SignedPublishResult.MissingSigner -> ready.copy(
                        isPosting = false,
                        postError = "秘密鍵が設定されていません",
                    )
                    is SignedPublishResult.Failed -> ready.copy(
                        isPosting = false,
                        postError = result.cause.message ?: "送信に失敗しました",
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
        val reactionEventId = currentLikedReactions[eventId] ?: return
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
        val reactionEventId = currentOwnEmojiReactionEventIds[eventId]?.get(option.key) ?: return
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

    fun unrepost(eventId: String) {
        val repostEventId = currentRepostedEvents[eventId] ?: return
        runEngagementOperation(
            eventId,
            EngagementRequest.RemoveRepost,
            NoteEngagementCommand.RemoveRepost(repostEventId),
            "リポストの解除に失敗しました",
        )
    }

    private fun runEngagementOperation(
        eventId: String,
        request: EngagementRequest,
        command: NoteEngagementCommand,
        failureMessage: String,
    ) {
        val operationId = EngagementOperationId("channel-${++nextEngagementOperationId}")
        val before = currentNoteEngagement(eventId)
        val optimistic = engagementCoordinator.begin(before, operationId, request)
        if (optimistic == before) return
        setCurrentEngagement(eventId, optimistic)
        syncReadyState()
        consumeEngagementError()
        launch {
            var committed = false
            var failure: Throwable? = null
            try {
                val published = engagementCoordinator.execute(command) { signed ->
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
                setCurrentEngagement(
                    eventId,
                    engagementCoordinator.commit(currentNoteEngagement(eventId), operationId, published.id),
                )
                syncReadyState()
                committed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            } finally {
                if (!committed) {
                    setCurrentEngagement(
                        eventId,
                        engagementCoordinator.rollback(currentNoteEngagement(eventId), operationId),
                    )
                    syncReadyState()
                }
            }
            if (failure != null) {
                val ready = _state.value as? UiState.Ready
                if (ready != null) _state.value = ready.copy(engagementError = failureMessage)
            }
        }
    }

    fun showEditThreadDialog() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(
            editDialog = EditThreadDialogState(
                title = current.channelMeta.name,
                description = current.channelMeta.about,
            ),
        )
    }

    fun dismissEditThreadDialog() {
        val current = _state.value as? UiState.Ready ?: return
        if (current.editDialog?.isSaving == true) return
        _state.value = current.copy(editDialog = null)
    }

    fun onEditTitleChange(title: String) {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(editDialog = current.editDialog?.copy(title = title, error = null))
    }

    fun onEditDescriptionChange(description: String) {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(editDialog = current.editDialog?.copy(description = description, error = null))
    }

    fun saveThreadMeta() {
        val current = _state.value as? UiState.Ready ?: return
        val dialog = current.editDialog ?: return
        if (dialog.title.isBlank() || dialog.isSaving) return
        _state.value = current.copy(editDialog = dialog.copy(isSaving = true, error = null))
        launch {
            val title = dialog.title.trim()
            val description = dialog.description.trim()
            val content = buildJsonObject {
                put("name", title)
                put("about", description)
                put("picture", currentChannelMeta.picture)
            }.toString()
            val result = signedEventPublisher.publish(
                content,
                41,
                listOf(listOf("e", channelId), listOf("client", "ToriNos")),
            )
            val ready = _state.value as? UiState.Ready ?: return@launch
            when (result) {
                is SignedPublishResult.Published -> {
                    currentChannelMeta = currentChannelMeta.copy(name = title, about = description)
                    _state.value = ready.copy(channelMeta = currentChannelMeta, editDialog = null)
                }
                SignedPublishResult.MissingSigner -> _state.value = ready.copy(
                    editDialog = ready.editDialog?.copy(
                        isSaving = false,
                        error = "秘密鍵が設定されていません",
                    ),
                )
                is SignedPublishResult.Failed -> _state.value = ready.copy(
                    editDialog = ready.editDialog?.copy(
                        isSaving = false,
                        error = result.cause.message ?: "保存に失敗しました",
                    ),
                )
            }
        }
    }

    fun loadMore() = history.older()
    fun loadHistoryGap() = history.fillGap()
    fun jumpToPrevious() = history.previous()
    fun jumpToLatest() = history.latest()
    fun retryMessages() = history.retry()
    fun consumeNavigation(sequence: Long) = history.consumeNavigation(sequence)
    fun consumeHistoryNotice() = history.consumeNotice()
    fun setAtLatest(value: Boolean) = history.setAtLatest(value)

    fun onViewport(visibleIds: Set<String>, anchorId: String?, offset: Int, savePosition: Boolean) {
        history.setViewport(anchorId)
        val visible = currentMessages.filter { it.id in visibleIds }
        visible.forEach { event ->
            scheduleProfileFetch(event.pubkey)
            scheduleMentionedProfileFetch(event.content)
            scheduleEngagementFetch(event.id)
        }
        val url = relayUrl ?: return
        val latestVisible = visible.maxOfOrNull { it.createdAt }
        if (latestVisible != null && latestVisible > lastMarkedReadAt) {
            lastMarkedReadAt = latestVisible
            launch { ChannelCacheStore.markRead(url, channelId, latestVisible) }
        }
        if (!savePosition || history.state.value.isLoading || history.state.value.navigation != null) return
        val anchor = visible.firstOrNull { it.id == anchorId } ?: return
        val position = ChannelReadingPosition(anchor.id, anchor.createdAt, offset.coerceAtLeast(0))
        pendingReadingPosition = position
        positionSaveJob?.cancel()
        positionSaveJob = launch {
            delay(POSITION_SAVE_DEBOUNCE_MS)
            saveReadingPosition(url, position)
        }
    }

    fun flushReadingPosition() {
        val url = relayUrl ?: return
        val position = pendingReadingPosition ?: return
        if (position == savedReadingPosition) return
        positionSaveJob?.cancel()
        // 画面破棄直後に ViewModel のスコープがキャンセルされても、最後の位置だけは保存を完了する。
        positionSaveJob = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { saveReadingPosition(url, position) }
        }
    }

    private suspend fun saveReadingPosition(url: String, position: ChannelReadingPosition) {
        ChannelCacheStore.saveReadingPosition(url, channelId, position)
        savedReadingPosition = position
    }

    private fun start() {
        jobs += launch {
            history.state.collect { snapshot ->
                currentMessages = snapshot.messages
                syncReadyState()
            }
        }

        // kind:40 でチャンネルメタ取得
        jobs += launch {
            NostrRepository.events(metaSubId).collect { event ->
                if (event.kind != 40) return@collect
                val meta = event.toChannelMeta() ?: return@collect
                currentChannelMeta = meta
                currentChannelOwnerPubkey = event.pubkey
                relayUrl?.let { ChannelCacheStore.upsertChannel(it, event, meta) }
                scheduleProfileFetch(event.pubkey)
                NostrRepository.subscribe(
                    metaUpdateSubId,
                    NostrFilter(kinds = listOf(41), eTags = listOf(channelId)),
                    relayUrl = relayUrl,
                )
                syncReadyState()
            }
        }

        // kind:41 チャンネルメタ更新
        jobs += launch {
            NostrRepository.events(metaUpdateSubId).collect { event ->
                if (event.kind != 41 || event.createdAt <= latestMetaUpdateCreatedAt) return@collect
                if (event.tags.none { it.firstOrNull() == "e" && it.getOrNull(1) == channelId }) return@collect
                val owner = currentChannelOwnerPubkey
                if (owner != null && event.pubkey != owner) return@collect
                val meta = event.toChannelMeta() ?: return@collect
                latestMetaUpdateCreatedAt = event.createdAt
                currentChannelMeta = meta
                syncReadyState()
            }
        }

        // ライブと有限履歴は別管理。履歴の確定前は新着もバッファに保持する。
        jobs += launch {
            NostrRepository.events(msgSubId).collect { event ->
                if (!noteContext.matches(event)) return@collect
                history.receive(event)
                relayUrl?.let { ChannelCacheStore.upsertMessage(it, event, channelId) }
            }
        }

        jobs += launch {
            NostrRepository.events(replyCountSubId).collect { event ->
                if (!noteContext.matches(event) || !seenReplyIds.add(event.id)) return@collect
                val targetId = noteContext.replyTargetId(event) ?: return@collect
                if (targetId !in watchedEventIds) return@collect
                currentReplyCounts = currentReplyCounts + (targetId to (currentReplyCounts[targetId] ?: 0) + 1)
                syncReadyState()
            }
        }

        jobs += launch {
            NostrRepository.events(reactionSubId).collect { event ->
                if (event.kind != 7 || !seenReactionIds.add(event.id)) return@collect
                rememberReceivedEvent(receivedReactionEvents, event)
                val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1) ?: return@collect
                if (targetId !in watchedEventIds) return@collect
                currentReactionCounts = currentReactionCounts + (targetId to (currentReactionCounts[targetId] ?: 0) + 1)
                if (event.content.trim() == "+") {
                    currentLikeReactionCounts = currentLikeReactionCounts + (
                        targetId to (currentLikeReactionCounts[targetId] ?: 0) + 1
                        )
                }
                event.toCustomReaction()?.let { reaction ->
                    currentCustomReactions = currentCustomReactions + (
                        targetId to currentCustomReactions[targetId]
                            .orEmpty()
                            .incrementedWith(reaction)
                    )
                }
                event.toUnicodeReaction()?.let { reaction ->
                    currentUnicodeReactions = currentUnicodeReactions + (
                        targetId to currentUnicodeReactions[targetId]
                            .orEmpty()
                            .incrementedWithUnicodeReaction(reaction)
                    )
                }
                currentReactionEvents = currentReactionEvents + (
                    targetId to currentReactionEvents[targetId].orEmpty().plus(event)
                )
                if (
                    ownPubkey != null &&
                    event.pubkey == ownPubkey &&
                    event.content.trim() == "+" &&
                    !currentLikedReactions.containsKey(targetId)
                ) {
                    currentLikedReactions = currentLikedReactions + (targetId to event.id)
                }
                if (ownPubkey != null && event.pubkey == ownPubkey) {
                    event.toReactionOption()?.let { option ->
                        currentOwnEmojiReactionEventIds = currentOwnEmojiReactionEventIds + (
                            targetId to currentOwnEmojiReactionEventIds[targetId].orEmpty()
                                .plus(option.key to event.id)
                            )
                    }
                }
                syncReadyState()
                scheduleProfileFetch(event.pubkey)
            }
        }

        jobs += launch {
            NostrRepository.events(repostSubId).collect { event ->
                if (event.kind != 6 || !seenRepostIds.add(event.id)) return@collect
                rememberReceivedEvent(receivedRepostEvents, event)
                val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1) ?: return@collect
                if (targetId !in watchedEventIds) return@collect
                currentRepostCounts = currentRepostCounts + (targetId to (currentRepostCounts[targetId] ?: 0) + 1)
                currentRepostPubkeys = currentRepostPubkeys + (
                    targetId to currentRepostPubkeys[targetId].orEmpty().plus(event.pubkey).distinct()
                )
                if (ownPubkey != null && event.pubkey == ownPubkey && !currentRepostedEvents.containsKey(targetId)) {
                    currentRepostedEvents = currentRepostedEvents + (targetId to event.id)
                }
                syncReadyState()
                scheduleProfileFetch(event.pubkey)
            }
        }

        jobs += launch {
            NostrRepository.events(quoteRepostSubId).collect { event ->
                if (!noteContext.matches(event)) return@collect
                val targetIds = event.tags
                    .filter { it.firstOrNull() == "q" }
                    .mapNotNull { it.getOrNull(1) }
                    .distinct()
                    .filter { it in watchedEventIds }
                    .filter { seenQuoteRepostIds.add("${event.id}:$it") }
                if (targetIds.isEmpty()) return@collect
                val counts = currentRepostCounts.toMutableMap()
                targetIds.forEach { targetId ->
                    counts[targetId] = (counts[targetId] ?: 0) + 1
                    currentRepostPubkeys = currentRepostPubkeys + (
                        targetId to currentRepostPubkeys[targetId].orEmpty().plus(event.pubkey).distinct()
                    )
                }
                currentRepostCounts = counts
                syncReadyState()
                scheduleProfileFetch(event.pubkey)
            }
        }

        // 共通プロフィールキャッシュを監視
        jobs += launch {
            ProfileRepository.observeAll().collect { cachedProfiles ->
                val profiles = cachedProfiles.filterKeys { it in pendingPubkeys || it in currentProfiles }
                if (profiles != currentProfiles) {
                    currentProfiles = profiles
                    syncReadyState()
                }
            }
        }

        // ミュート・NGワード変更時に表示リストを再フィルタ
        jobs += launch {
            accountSession?.muteStore?.mutedPubkeys?.collect { syncReadyState() }
        }
        jobs += launch {
            accountSession?.ngWordStore?.ngWords?.collect { syncReadyState() }
        }

        jobs += launch {
            val url = relayUrl
            val cachedState = try {
                withTimeoutOrNull(10_000) {
                    val cached = if (url != null) ChannelCacheStore.getMessages(url, channelId, ChannelHistory.PAGE_SIZE) else emptyList()
                    cached to url?.let { ChannelCacheStore.getReadingPosition(it, channelId) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logException("ChannelController", error, "Could not read channel cache")
                null
            }
            history.initialize(cachedState?.first.orEmpty(), cachedState?.second)
            NostrRepository.subscribe(metaSubId, NostrFilter(ids = listOf(channelId)), relayUrl = relayUrl)
            NostrRepository.subscribe(
                msgSubId,
                NostrFilter(kinds = listOf(42), eTags = listOf(channelId), since = openedAt),
                relayUrl = relayUrl,
            )
        }
    }

    private fun currentNoteEngagement(eventId: String): NoteEngagementState = NoteEngagementState(
        reactionCount = currentReactionCounts[eventId] ?: 0,
        likeReactionCount = currentLikeReactionCounts[eventId] ?: 0,
        customReactions = currentCustomReactions[eventId].orEmpty(),
        unicodeReactions = currentUnicodeReactions[eventId].orEmpty(),
        ownLikeEventId = currentLikedReactions[eventId],
        ownEmojiReactionEventIds = currentOwnEmojiReactionEventIds[eventId].orEmpty(),
        repostCount = currentRepostCounts[eventId] ?: 0,
        ownRepostEventId = currentRepostedEvents[eventId],
        pendingOperations = currentPendingEngagementOperations[eventId].orEmpty(),
    )

    private fun setCurrentEngagement(eventId: String, engagement: NoteEngagementState) {
        currentReactionCounts = currentReactionCounts + (eventId to engagement.reactionCount)
        currentLikeReactionCounts = currentLikeReactionCounts + (eventId to engagement.likeReactionCount)
        currentCustomReactions = currentCustomReactions.putListOrRemove(eventId, engagement.customReactions)
        currentUnicodeReactions = currentUnicodeReactions.putListOrRemove(eventId, engagement.unicodeReactions)
        currentLikedReactions = currentLikedReactions.putOrRemove(eventId, engagement.ownLikeEventId)
        currentOwnEmojiReactionEventIds = currentOwnEmojiReactionEventIds.putMapOrRemove(
            eventId,
            engagement.ownEmojiReactionEventIds,
        )
        currentRepostCounts = currentRepostCounts + (eventId to engagement.repostCount)
        currentRepostedEvents = currentRepostedEvents.putOrRemove(eventId, engagement.ownRepostEventId)
        currentPendingEngagementOperations = currentPendingEngagementOperations.putMapOrRemove(
            eventId,
            engagement.pendingOperations,
        )
    }

    private fun readyState(canLoadMore: Boolean): UiState.Ready =
        UiState.Ready(
            channelMeta = currentChannelMeta,
            channelOwnerPubkey = currentChannelOwnerPubkey,
            messages = filteredMessages(),
            profiles = currentProfiles,
            replyCounts = currentReplyCounts,
            reactionCounts = currentReactionCounts,
            likeReactionCounts = currentLikeReactionCounts,
            customReactions = currentCustomReactions,
            unicodeReactions = currentUnicodeReactions,
            reactionEvents = currentReactionEvents,
            repostCounts = currentRepostCounts,
            repostPubkeys = currentRepostPubkeys,
            likedReactions = currentLikedReactions,
            ownEmojiReactionEventIds = currentOwnEmojiReactionEventIds,
            repostedEvents = currentRepostedEvents,
            pendingEngagementOperations = currentPendingEngagementOperations,
            canLoadMore = canLoadMore,
            history = history.state.value,
        )

    private fun syncReadyState() {
        val current = _state.value as? UiState.Ready ?: readyState(canLoadMore = false)
        _state.value = current.copy(
            history = history.state.value,
            canLoadMore = history.state.value.canLoadOlder,
            channelMeta = currentChannelMeta,
            channelOwnerPubkey = currentChannelOwnerPubkey,
            messages = filteredMessages(),
            profiles = currentProfiles,
            replyCounts = currentReplyCounts,
            reactionCounts = currentReactionCounts,
            likeReactionCounts = currentLikeReactionCounts,
            customReactions = currentCustomReactions,
            unicodeReactions = currentUnicodeReactions,
            reactionEvents = currentReactionEvents,
            repostCounts = currentRepostCounts,
            repostPubkeys = currentRepostPubkeys,
            likedReactions = currentLikedReactions,
            ownEmojiReactionEventIds = currentOwnEmojiReactionEventIds,
            repostedEvents = currentRepostedEvents,
            pendingEngagementOperations = currentPendingEngagementOperations,
        )
    }

    private fun filteredMessages(): List<NostrEvent> {
        val muted = accountSession?.muteStore?.mutedPubkeys?.value.orEmpty()
        val ngWords = accountSession?.ngWordStore?.ngWords?.value.orEmpty()
        return currentMessages.filter { msg ->
            !muted.contains(msg.pubkey) &&
                (ngWords.isEmpty() || ngWords.none { msg.content.contains(it, ignoreCase = true) })
        }
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in currentProfiles || pubkey in pendingPubkeys) return
        pendingPubkeys.add(pubkey)
        ProfileRepository.getCached(pubkey)?.let { profile ->
            currentProfiles = currentProfiles + (pubkey to profile)
            syncReadyState()
        }
        profileBatchJob?.cancel()
        profileBatchJob = launch {
            delay(500)
            if (pendingPubkeys.isEmpty()) return@launch
            ProfileRepository.ensureProfiles(
                pendingPubkeys.toSet(),
                ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS),
                relayHint = relayUrl,
            )
        }
    }

    private fun scheduleMentionedProfileFetch(text: String) {
        extractNpubReferences(text).forEach { reference ->
            scheduleProfileFetch(reference.pubkey)
        }
    }

    private fun scheduleEngagementFetch(eventId: String) {
        if (!watchedEventIds.add(eventId)) return
        while (watchedEventIds.size > MAX_WATCHED_EVENTS) watchedEventIds.remove(watchedEventIds.first())
        engagementBatchJob?.cancel()
        engagementBatchJob = launch {
            delay(300)
            val ids = watchedEventIds.toList()
            NostrRepository.subscribe(
                replyCountSubId,
                NostrFilter(kinds = listOf(noteContext.eventKind), eTags = ids, limit = 500),
                relayUrl = relayUrl,
            )
            NostrRepository.subscribe(
                reactionSubId,
                NostrFilter(kinds = listOf(7), eTags = ids, limit = 500),
                relayUrl = relayUrl,
            )
            NostrRepository.subscribe(
                repostSubId,
                NostrFilter(kinds = listOf(6), eTags = ids, limit = 500),
                relayUrl = relayUrl,
            )
            NostrRepository.subscribe(
                quoteRepostSubId,
                NostrFilter(kinds = listOf(noteContext.eventKind), qTags = ids, limit = 500),
                relayUrl = relayUrl,
            )
        }
    }

    fun close() {
        flushReadingPosition()
        jobs.forEach { it.cancel() }
        jobs.clear()
        lifecycleJobs.forEach { it.cancel() }
        lifecycleJobs.clear()
        profileBatchJob?.cancel()
        engagementBatchJob?.cancel()
        history.close()
        NostrRepository.close(metaSubId)
        NostrRepository.close(metaUpdateSubId)
        NostrRepository.close(msgSubId)
        NostrRepository.close(histSubId)
        NostrRepository.close(replyCountSubId)
        NostrRepository.close(reactionSubId)
        NostrRepository.close(repostSubId)
        NostrRepository.close(quoteRepostSubId)
    }

    private fun rememberReceivedEvent(events: LinkedHashMap<String, NostrEvent>, event: NostrEvent) {
        events[event.id] = event
        while (events.size > MAX_SEEN_IDS) events.remove(events.keys.first())
    }

    private fun reconcileOwnEngagement() {
        val pubkey = ownPubkey ?: return
        receivedReactionEvents.values.filter { it.pubkey == pubkey }.forEach { event ->
            val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                ?: return@forEach
            if (event.content.trim() == "+") {
                currentLikedReactions = currentLikedReactions + (targetId to event.id)
            }
            event.toReactionOption()?.let { option ->
                currentOwnEmojiReactionEventIds = currentOwnEmojiReactionEventIds + (
                    targetId to currentOwnEmojiReactionEventIds[targetId].orEmpty().plus(option.key to event.id)
                )
            }
        }
        receivedRepostEvents.values.filter { it.pubkey == pubkey }.forEach { event ->
            val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                ?: return@forEach
            currentRepostedEvents = currentRepostedEvents + (targetId to event.id)
        }
        syncReadyState()
    }

    companion object {
        private const val MAX_SEEN_IDS = 1000
        private const val MAX_WATCHED_EVENTS = 100
        private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
        private const val POSITION_SAVE_DEBOUNCE_MS = 400L
    }
}

internal fun ChannelViewModel.UiState.Ready.noteEngagement(eventId: String): NoteEngagementState = NoteEngagementState(
    reactionCount = reactionCounts[eventId] ?: 0,
    likeReactionCount = likeReactionCounts[eventId] ?: 0,
    customReactions = customReactions[eventId].orEmpty(),
    unicodeReactions = unicodeReactions[eventId].orEmpty(),
    ownLikeEventId = likedReactions[eventId],
    ownEmojiReactionEventIds = ownEmojiReactionEventIds[eventId].orEmpty(),
    repostCount = repostCounts[eventId] ?: 0,
    ownRepostEventId = repostedEvents[eventId],
    pendingOperations = pendingEngagementOperations[eventId].orEmpty(),
)

private fun <K, V> Map<K, V>.putOrRemove(key: K, value: V?): Map<K, V> =
    if (value == null) this - key else this + (key to value)

private fun <K, V> Map<K, List<V>>.putListOrRemove(key: K, value: List<V>): Map<K, List<V>> =
    if (value.isEmpty()) this - key else this + (key to value)

private fun <K, K2, V2> Map<K, Map<K2, V2>>.putMapOrRemove(
    key: K,
    value: Map<K2, V2>,
): Map<K, Map<K2, V2>> = if (value.isEmpty()) this - key else this + (key to value)
