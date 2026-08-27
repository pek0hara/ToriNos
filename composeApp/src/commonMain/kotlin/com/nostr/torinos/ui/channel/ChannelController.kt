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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
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
    private fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        safeCoroutineLauncher.launch(block = block)

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

    private val seenIds = linkedSetOf<String>()
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
    private var pageTimeoutJob: Job? = null
    private val jobs = mutableListOf<Job>()
    private val lifecycleJobs = mutableListOf<Job>()

    private var oldestCreatedAt: Long? = null
    private var loadingMore = false
    private var isInitialDiffFetch = false
    private var isInitialPageRequest = false
    private var lastBatchCount = 0
    private var receivedEoseCount = 0
    private var expectedEoseCount = 1
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
    private var currentRepostCounts = emptyMap<String, Int>()
    private var currentLikedReactions = emptyMap<String, String>()
    private var currentOwnEmojiReactionEventIds = emptyMap<String, Map<String, String>>()
    private var currentRepostedEvents = emptyMap<String, String>()
    private var currentPendingEngagementOperations = emptyMap<String, Map<EngagementSlot, PendingEngagementOperation>>()
    private var ownPubkey: String? = null
    private val engagementCoordinator = NoteEngagementCoordinator(accountSession?.signer)
    private val signedEventPublisher = SignedEventPublisher(accountSession?.signer)
    private var nextEngagementOperationId = 0L
    private val noteContext = NoteContext.Channel(channelId)

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

    fun loadMore() {
        if (loadingMore || (_state.value as? UiState.Ready)?.canLoadMore != true) return
        launch {
            requestPage(until = oldestCreatedAt?.minus(1))
        }
    }

    private fun start() {
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

        // kind:42 メッセージ受信（ライブ）
        jobs += launch {
            NostrRepository.events(msgSubId).collect { event ->
                if (!noteContext.matches(event)) return@collect
                appendMessage(event)
                relayUrl?.let { ChannelCacheStore.upsertMessage(it, event, channelId) }
                markLatestRead()
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
                scheduleEngagementFetch(event.id)
            }
        }

        // kind:42 メッセージ受信（過去ページ）- EOSE 後に onPageCompleted でまとめて反映
        jobs += launch {
            NostrRepository.events(histSubId).collect { event ->
                if (!noteContext.matches(event)) return@collect
                val added = appendMessage(event, notify = false)
                lastBatchCount += added
                relayUrl?.let { ChannelCacheStore.upsertMessage(it, event, channelId) }
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
                scheduleEngagementFetch(event.id)
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
                targetIds.forEach { targetId -> counts[targetId] = (counts[targetId] ?: 0) + 1 }
                currentRepostCounts = counts
                syncReadyState()
            }
        }

        // リレーが msgSubId を CLOSED したら再購読
        jobs += launch {
            NostrRepository.closed(msgSubId).collect {
                // 降順リストなので firstOrNull() が最新 createdAt
                val sinceTs = currentMessages.firstOrNull()?.createdAt
                    ?: Clock.System.now().epochSeconds
                NostrRepository.subscribe(
                    msgSubId,
                    NostrFilter(kinds = listOf(noteContext.eventKind), eTags = listOf(channelId), since = sinceTs),
                    relayUrl = relayUrl,
                )
            }
        }

        // EOSE でローディング解除
        jobs += launch {
            NostrRepository.eose(histSubId).collect {
                receivedEoseCount++
                if (receivedEoseCount >= expectedEoseCount) {
                    onPageCompleted()
                }
            }
        }

        // タイムアウトフォールバック
        jobs += launch {
            delay(10_000)
            if (_state.value is UiState.Loading) {
                _state.value = readyState(canLoadMore = false)
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

        launch {
            val cacheRelayUrl = relayUrl
            if (cacheRelayUrl != null) {
                // DB からキャッシュ済みメッセージを先に読み込んで即時表示
                val cached = ChannelCacheStore.getMessages(cacheRelayUrl, channelId)
                if (cached.isNotEmpty()) {
                    cached.forEach {
                        appendMessage(it)
                        scheduleProfileFetch(it.pubkey)
                        scheduleMentionedProfileFetch(it.content)
                        scheduleEngagementFetch(it.id)
                    }
                    _state.value = readyState(canLoadMore = false)
                }
            }
            // チャンネルメタ取得
            NostrRepository.subscribe(metaSubId, NostrFilter(ids = listOf(channelId)), relayUrl = relayUrl)
            // 初回取得: DB に何かあれば最新以降の差分のみ、無ければ最新 PAGE_SIZE
            // 降順リストなので firstOrNull() が最新 createdAt
            startInitialFetch(cacheLatest = currentMessages.firstOrNull()?.createdAt)
        }
    }

    private suspend fun startInitialFetch(cacheLatest: Long?) {
        isInitialPageRequest = true
        isInitialDiffFetch = cacheLatest != null
        loadingMore = true
        lastBatchCount = 0
        receivedEoseCount = 0
        expectedEoseCount = if (relayUrl != null) 1 else NostrRepository.relayCount.coerceAtLeast(1)
        val current = _state.value as? UiState.Ready
        if (current != null) {
            _state.value = current.copy(canLoadMore = false)
        }
        schedulePageTimeout()

        // ライブ購読: 起動時刻以降の新着のみ受信（リレー default cap 分の過去履歴を流させない）
        NostrRepository.subscribe(
            msgSubId,
            NostrFilter(
                kinds = listOf(noteContext.eventKind),
                eTags = listOf(channelId),
                since = Clock.System.now().epochSeconds,
            ),
            relayUrl = relayUrl,
        )

        // 履歴ページ: キャッシュがあれば最新 createdAt 以降の差分のみ、無ければ最新 PAGE_SIZE 件
        val histFilter = if (cacheLatest != null) {
            NostrFilter(kinds = listOf(noteContext.eventKind), eTags = listOf(channelId), since = cacheLatest + 1)
        } else {
            NostrFilter(kinds = listOf(noteContext.eventKind), eTags = listOf(channelId), until = null, limit = PAGE_SIZE)
        }
        NostrRepository.subscribe(histSubId, histFilter, relayUrl = relayUrl)
    }

    private suspend fun requestPage(until: Long?) {
        isInitialPageRequest = false
        loadingMore = true
        lastBatchCount = 0
        receivedEoseCount = 0
        expectedEoseCount = if (relayUrl != null) 1 else NostrRepository.relayCount.coerceAtLeast(1)
        val current = _state.value as? UiState.Ready
        if (current != null) {
            _state.value = current.copy(canLoadMore = false)
        }
        schedulePageTimeout()
        NostrRepository.subscribe(
            histSubId,
            NostrFilter(kinds = listOf(noteContext.eventKind), eTags = listOf(channelId), until = until, limit = PAGE_SIZE),
            relayUrl = relayUrl,
        )
    }

    private fun onPageCompleted() {
        if (!loadingMore) return
        loadingMore = false
        pageTimeoutJob?.cancel()
        pageTimeoutJob = null
        // リレー間の重複除外で PAGE_SIZE 未満になることがあるため、
        // 1件でも増えたページでは次の古いページ取得を許可する。
        val hasMore = if (isInitialDiffFetch) true else lastBatchCount > 0
        isInitialPageRequest = false
        isInitialDiffFetch = false
        _state.value = readyState(canLoadMore = hasMore)
        markLatestRead()
        NostrRepository.close(histSubId)
    }

    private fun schedulePageTimeout() {
        pageTimeoutJob?.cancel()
        pageTimeoutJob = launch {
            delay(10_000)
            if (!loadingMore) return@launch

            loadingMore = false
            val hasMore = if (isInitialDiffFetch) true else lastBatchCount > 0
            isInitialPageRequest = false
            isInitialDiffFetch = false
            _state.value = readyState(canLoadMore = hasMore)
            markLatestRead()
            if (!hasMore) NostrRepository.close(histSubId)
        }
    }

    private fun appendMessage(event: NostrEvent, notify: Boolean = true): Int {
        if (!seenIds.add(event.id)) return 0
        while (seenIds.size > MAX_SEEN_IDS) seenIds.remove(seenIds.first())
        if (currentMessages.any { it.id == event.id }) return 0
        currentMessages = ChannelMessageReducer.received(currentMessages, event)
        oldestCreatedAt = currentMessages.lastOrNull()?.createdAt
        if (notify) syncReadyState()
        return 1
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
            repostCounts = currentRepostCounts,
            likedReactions = currentLikedReactions,
            ownEmojiReactionEventIds = currentOwnEmojiReactionEventIds,
            repostedEvents = currentRepostedEvents,
            pendingEngagementOperations = currentPendingEngagementOperations,
            canLoadMore = canLoadMore,
        )

    private fun syncReadyState() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(
            channelMeta = currentChannelMeta,
            channelOwnerPubkey = currentChannelOwnerPubkey,
            messages = filteredMessages(),
            profiles = currentProfiles,
            replyCounts = currentReplyCounts,
            reactionCounts = currentReactionCounts,
            likeReactionCounts = currentLikeReactionCounts,
            customReactions = currentCustomReactions,
            unicodeReactions = currentUnicodeReactions,
            repostCounts = currentRepostCounts,
            likedReactions = currentLikedReactions,
            ownEmojiReactionEventIds = currentOwnEmojiReactionEventIds,
            repostedEvents = currentRepostedEvents,
            pendingEngagementOperations = currentPendingEngagementOperations,
        )
    }

    private fun markLatestRead() {
        val cacheRelayUrl = relayUrl ?: return
        // 降順リストなので firstOrNull() が最新 createdAt
        val latestReadAt = currentMessages.firstOrNull()?.createdAt ?: return
        launch {
            ChannelCacheStore.markRead(cacheRelayUrl, channelId, latestReadAt)
        }
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
        jobs.forEach { it.cancel() }
        jobs.clear()
        lifecycleJobs.forEach { it.cancel() }
        lifecycleJobs.clear()
        profileBatchJob?.cancel()
        engagementBatchJob?.cancel()
        pageTimeoutJob?.cancel()
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
        private const val PAGE_SIZE = 30
        private const val MAX_SEEN_IDS = 1000
        private const val MAX_WATCHED_EVENTS = 100
        private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
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
