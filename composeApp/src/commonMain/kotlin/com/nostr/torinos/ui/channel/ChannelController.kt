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
import com.nostr.torinos.model.ChannelEventTags
import com.nostr.torinos.model.ChannelMetadataResolver
import com.nostr.torinos.model.toChannelContent
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
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toUnicodeReaction
import com.nostr.torinos.model.toReactionOption
import com.nostr.torinos.network.ChannelReadingPosition
import com.nostr.torinos.model.ChannelRelayContext
import com.nostr.torinos.network.RelayConnectionState
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.network.ChannelLocalStateStore
import com.nostr.torinos.network.ChannelLocalStore
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
import com.nostr.torinos.util.networkTraceLog
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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
    // kind 40/41の候補をevent ID単位で保持し、到着順に依存せずChannelMetadataResolverを再実行する(第16.4節)。
    private var channelCreateEvent: NostrEvent? = null
    private val metadataUpdateCandidates = linkedMapOf<String, NostrEvent>()
    private var effectiveMetadataSourceEventId: String? = null
    // 端末に保存済みの実効メタデータの取得元。kind 40 が kind 41 より先に届いても巻き戻さないために使う。
    private var cachedMetadataSource: CachedMetadataSource? = null
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
    private val locallyDeletedMessageIds = mutableSetOf<String>()
    private var ownPubkey: String? = null
    private val engagementCoordinator = NoteEngagementCoordinator(accountSession?.signer)
    private val signedEventPublisher = SignedEventPublisher(accountSession?.signer)
    private var nextEngagementOperationId = 0L
    private var publishSequence = 0L
    private val noteContext = NoteContext.Channel(channelId)
    private val openedAt = Clock.System.now().epochSeconds

    // チャンネル固有の relay context(第16.5節)。表示・購読は常にこの値から決める。
    private val navigationRelayHint = ChannelRelayPlanner.normalizeHint(relayUrl)
    private var relayContext = ChannelRelayContext.EMPTY
    private var readTarget: RelayTarget = ChannelRelayPlanner.readTarget(emptySet(), navigationRelayHint)
    private var relayContextGeneration = 0L
    private var relayTransitionJob: Job? = null
    private var isRelayTransitioning = false
    // relay context 変更時に張り直すライブ購読。subId -> filter。
    private val liveFilters = linkedMapOf<String, NostrFilter>()

    private val history = ChannelHistory(
        scope = scope,
        channelId = channelId,
        fetch = ::fetchHistoryPage,
        lookup = { id ->
            fetchHistoryPage(NostrFilter(ids = listOf(id), kinds = listOf(42), limit = 1))
                .events.firstOrNull()
        },
    )

    private suspend fun fetchHistoryPage(filter: NostrFilter): ChannelHistoryPage {
        val events = mutableListOf<NostrEvent>()
        val complete = fetchChannelEvents(
            SubscriptionSpec(
                id = "$histSubId-${requestSequence++}",
                filters = listOf(filter),
                target = readTarget,
                behavior = SubscriptionBehavior.Fetch(10_000),
            ),
            // 応答しない推奨リレーが1件あってもページ全体を未完了にしない。
            settleAfterFirstEoseMillis = HISTORY_SETTLE_MS,
        ) { event -> if (noteContext.matches(event)) events.add(event) }
        // メッセージ本体は端末へ保存しない(第16.12節)。一覧プレビュー用に最新1件だけ記録する。
        val retainedEvents = events.filterNot { it.id in locallyDeletedMessageIds }.distinctBy { it.id }
        retainedEvents.maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
            ?.let { recordLatestMessage(it) }
        return ChannelHistoryPage(retainedEvents, complete)
    }

    private suspend fun recordLatestMessage(event: NostrEvent) {
        try {
            ChannelLocalStore.recordLatestMessage(channelId, event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logException("ChannelController", error, "Could not record latest channel message")
        }
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

        // 送信先と relay hint は開始時点で固定し、送信中の kind 41 更新の影響を受けない(第16.9節)。
        val publishContext = ChannelPublishContext.from(relayContext)
        val sequence = ++publishSequence
        _state.value = current.copy(
            isPosting = true,
            postError = null,
            publishState = ChannelPublishUiState.sending(publishContext.targetsForDisplay),
        )
        launch {
            val result = signedEventPublisher.publish(
                content = text,
                kind = noteContext.eventKind,
                tags = ChannelEventTags.rootMessage(channelId, publishContext.primaryHint) +
                    listOf(listOf("client", "ToriNos")),
                relayUrls = publishContext.relayUrls,
                // 最初の受理後に届く残りのリレーの結果。リポジトリのスコープから呼ばれるため画面側へ戻す。
                onRelayResult = { relayResult ->
                    launch { updatePublishState(sequence) { it.withRelayResult(relayResult) } }
                },
            )
            val publishState = ChannelPublishUiState.from(publishContext.targetsForDisplay, result)
                .let { fresh -> (_state.value as? UiState.Ready)?.publishState?.let { mergeProgress(fresh, it) } ?: fresh }
            if (result is SignedPublishResult.Published) {
                // リレーからの echo を待たずに表示する。event ID で重複排除される。
                history.receive(result.event)
                recordLatestMessage(result.event)
            }
            (_state.value as? UiState.Ready)?.let { ready ->
                _state.value = when (result) {
                    // 1件以上成功なら投稿済み。一部失敗は publishState に残して案内する。
                    is SignedPublishResult.Published -> ready.copy(
                        draftText = "",
                        isPosting = false,
                        publishState = publishState,
                    )
                    SignedPublishResult.MissingSigner -> ready.copy(
                        isPosting = false,
                        postError = "秘密鍵が設定されていません",
                        publishState = publishState,
                    )
                    // 全件失敗は下書きを残して再試行できるようにする。
                    is SignedPublishResult.Failed -> ready.copy(
                        isPosting = false,
                        postError = publishState.summary ?: result.cause.message ?: "送信に失敗しました",
                        publishState = publishState,
                    )
                }
            }
        }
    }

    private fun updatePublishState(sequence: Long, transform: (ChannelPublishUiState) -> ChannelPublishUiState) {
        if (sequence != publishSequence) return
        val ready = _state.value as? UiState.Ready ?: return
        _state.value = ready.copy(publishState = transform(ready.publishState))
    }

    /** 戻り値より先に届いた他リレーの結果を失わないよう統合する。 */
    private fun mergeProgress(fresh: ChannelPublishUiState, progress: ChannelPublishUiState): ChannelPublishUiState =
        if (fresh.phase == ChannelPublishUiState.Phase.Failed || progress.targets != fresh.targets) {
            fresh
        } else {
            fresh.withRelayResult(RelayPublishResult(progress.succeeded, progress.failed))
        }

    fun deleteMessage(eventId: String) {
        val event = currentMessages.firstOrNull { it.id == eventId } ?: return
        if (ownPubkey == null || event.pubkey != ownPubkey) return
        launch {
            // 削除要求はメッセージを配送したチャンネルの書き込み先へ送る。
            val result = signedEventPublisher.publish(
                content = "",
                kind = 5,
                tags = listOf(listOf("e", eventId), listOf("k", event.kind.toString())),
                relayUrls = ChannelPublishContext.from(relayContext).relayUrls,
            )
            when (result) {
                is SignedPublishResult.Published -> {
                    locallyDeletedMessageIds += eventId
                    history.remove(eventId)
                    ChannelLocalStore.clearLatestMessage(channelId, eventId)
                }
                SignedPublishResult.MissingSigner -> {
                    val ready = _state.value as? UiState.Ready
                    if (ready != null) _state.value = ready.copy(engagementError = "秘密鍵が設定されていません")
                }
                is SignedPublishResult.Failed -> {
                    val ready = _state.value as? UiState.Ready
                    if (ready != null) {
                        _state.value = ready.copy(
                            engagementError = result.cause.message ?: "削除要求の送信に失敗しました",
                        )
                    }
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
        // UI の表示条件だけに頼らず、署名前に所有者であることを再検証する(第16.10節)。
        if (ownPubkey == null || ownPubkey != currentChannelOwnerPubkey) {
            _state.value = current.copy(editDialog = dialog.copy(error = "チャンネルの作成者だけが編集できます"))
            return
        }
        _state.value = current.copy(editDialog = dialog.copy(isSaving = true, error = null))
        val publishContext = ChannelPublishContext.from(relayContext)
        launch {
            // 現在の実効メタデータから完全な内容を作る。relays・picture を落とすと推奨リレーが消える。
            val meta = currentChannelMeta.copy(name = dialog.title.trim(), about = dialog.description.trim())
            val result = signedEventPublisher.publish(
                content = meta.toChannelContent(),
                kind = 41,
                tags = ChannelEventTags.metadata(channelId, publishContext.primaryHint, categories = emptyList()) +
                    listOf(listOf("client", "ToriNos")),
                relayUrls = publishContext.relayUrls,
            )
            when (result) {
                is SignedPublishResult.Published -> {
                    // 自己発行分も受信イベントと同じ resolver 経路で反映する(第21.2節4)。
                    metadataUpdateCandidates[result.event.id] = result.event
                    if (channelCreateEvent != null) {
                        applyMetadataResolution()
                    } else {
                        currentChannelMeta = meta
                    }
                    val ready = _state.value as? UiState.Ready ?: return@launch
                    val partial = ChannelPublishUiState.from(publishContext.targetsForDisplay, result).summary
                    _state.value = ready.copy(
                        channelMeta = currentChannelMeta,
                        editDialog = null,
                        engagementError = partial?.let { "チャンネル情報を保存しました（$it）" },
                    )
                }
                SignedPublishResult.MissingSigner -> (_state.value as? UiState.Ready)?.let { ready ->
                    _state.value = ready.copy(
                        editDialog = ready.editDialog?.copy(isSaving = false, error = "秘密鍵が設定されていません"),
                    )
                }
                is SignedPublishResult.Failed -> (_state.value as? UiState.Ready)?.let { ready ->
                    val summary = ChannelPublishUiState.from(publishContext.targetsForDisplay, result).summary
                    _state.value = ready.copy(
                        editDialog = ready.editDialog?.copy(
                            isSaving = false,
                            error = summary ?: result.cause.message ?: "保存に失敗しました",
                        ),
                    )
                }
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
        val latestVisible = visible.maxOfOrNull { it.createdAt }
        if (latestVisible != null && latestVisible > lastMarkedReadAt) {
            lastMarkedReadAt = latestVisible
            launch { ChannelLocalStore.markRead(channelId, latestVisible) }
        }
        if (!savePosition || history.state.value.isLoading || history.state.value.navigation != null) return
        val anchor = visible.firstOrNull { it.id == anchorId } ?: return
        val position = ChannelReadingPosition(anchor.id, anchor.createdAt, offset.coerceAtLeast(0))
        pendingReadingPosition = position
        positionSaveJob?.cancel()
        positionSaveJob = launch {
            delay(POSITION_SAVE_DEBOUNCE_MS)
            saveReadingPosition(position)
        }
    }

    fun flushReadingPosition() {
        val position = pendingReadingPosition ?: return
        if (position == savedReadingPosition) return
        positionSaveJob?.cancel()
        // 画面破棄直後に ViewModel のスコープがキャンセルされても、最後の位置だけは保存を完了する。
        positionSaveJob = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { saveReadingPosition(position) }
        }
    }

    private suspend fun saveReadingPosition(position: ChannelReadingPosition) {
        ChannelLocalStore.saveReadingPosition(channelId, position)
        savedReadingPosition = position
    }

    /**
     * 保持済みのkind 40/41候補からChannelMetadataResolverを再実行し、実効メタデータが変わった場合だけ
     * 状態とキャッシュを更新する。到着順・受信リレー順に依存しない決定的な選択にする(第16.4節)。
     */
    private suspend fun applyMetadataResolution() {
        val create = channelCreateEvent ?: return
        val resolution = ChannelMetadataResolver.resolve(
            channelId = channelId,
            createCandidates = listOf(create),
            updateCandidates = metadataUpdateCandidates.values,
        ) ?: return
        if (resolution.effectiveEvent.id == effectiveMetadataSourceEventId) return
        if (shouldKeepCachedMetadata(cachedMetadataSource, create.pubkey, resolution.effectiveEvent)) {
            // 保存済みの kind 41 の方が新しい。同じか新しい kind 41 が届くまで表示・購読先を戻さない。
            currentChannelOwnerPubkey = create.pubkey
            syncReadyState()
            return
        }
        effectiveMetadataSourceEventId = resolution.effectiveEvent.id
        currentChannelMeta = resolution.metadata
        currentChannelOwnerPubkey = resolution.channelCreateEvent.pubkey
        updateRelayContext(resolution.metadata.relays)
        try {
            ChannelLocalStore.upsertChannelMetadata(
                channelCreateEvent = resolution.channelCreateEvent,
                effectiveEvent = resolution.effectiveEvent,
                metadata = resolution.metadata,
                observedRelayUrl = relayUrl,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logException("ChannelController", error, "Could not cache channel metadata")
        }
        syncReadyState()
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
                if (event.kind != 40 || event.id != channelId || channelCreateEvent != null) return@collect
                channelCreateEvent = event
                applyMetadataResolution()
                scheduleProfileFetch(event.pubkey)
                subscribeLive(metaUpdateSubId, NostrFilter(kinds = listOf(41), eTags = listOf(channelId)))
            }
        }

        // kind:41 チャンネルメタ更新。所有者・root marker・JSON妥当性の検証はChannelMetadataResolverへ委譲する。
        jobs += launch {
            NostrRepository.events(metaUpdateSubId).collect { event ->
                if (event.kind != 41) return@collect
                metadataUpdateCandidates[event.id] = event
                applyMetadataResolution()
            }
        }

        // ライブと有限履歴は別管理。履歴の確定前は新着もバッファに保持する。
        jobs += launch {
            NostrRepository.events(msgSubId).collect { event ->
                if (!noteContext.matches(event)) return@collect
                history.receive(event)
                recordLatestMessage(event)
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
            ProfileRepository.observeChanges().collect { changedPubkeys ->
                val targets = pendingPubkeys + currentProfiles.keys
                val affected = if (changedPubkeys.isEmpty()) targets else changedPubkeys.intersect(targets)
                if (affected.isEmpty()) return@collect
                val profiles = currentProfiles - affected + ProfileRepository.getCached(affected)
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
            val localState = try {
                withTimeoutOrNull(10_000) { ChannelLocalStore.get(channelId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logException("ChannelController", error, "Could not read channel local state")
                null
            }
            // 保存済みの実効メタデータを暫定表示し、kind 40/41 の受信後に resolver の結果で置き換える(第16.6節)。
            if (localState != null && localState.hasMetadata && effectiveMetadataSourceEventId == null) {
                currentChannelMeta = localState.meta
                currentChannelOwnerPubkey = localState.ownerPubkey
                cachedMetadataSource = CachedMetadataSource(
                    ownerPubkey = localState.ownerPubkey,
                    eventId = localState.metadataEventId,
                    createdAt = localState.metadataCreatedAt,
                )
            }
            // 保存済みの推奨リレーで暫定 context を作り、最初の購読からその集合を使う(第16.6節 初期化順1)。
            relayContext = planRelayContext(currentChannelMeta.relays)
            readTarget = ChannelRelayPlanner.readTarget(relayContext.readRelays, navigationRelayHint)
            logRelayContext("initial")
            syncReadyState()
            // メッセージ本体はローカルに持たないため、履歴は常にリレー取得から始める。
            history.initialize(emptyList(), localState?.readingPosition)
            subscribeLive(metaSubId, NostrFilter(ids = listOf(channelId)))
            subscribeLive(msgSubId, NostrFilter(kinds = listOf(42), eTags = listOf(channelId), since = openedAt))
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
            relayContext = relayContext,
            isRelayTransitioning = isRelayTransitioning,
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
            relayContext = relayContext,
            isRelayTransitioning = isRelayTransitioning,
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
            // 返信・リアクション集計もメッセージと同じ read relay context を使う(FR-05)。
            subscribeLive(replyCountSubId, NostrFilter(kinds = listOf(noteContext.eventKind), eTags = ids, limit = 500))
            subscribeLive(reactionSubId, NostrFilter(kinds = listOf(7), eTags = ids, limit = 500))
            subscribeLive(repostSubId, NostrFilter(kinds = listOf(6), eTags = ids, limit = 500))
            subscribeLive(quoteRepostSubId, NostrFilter(kinds = listOf(noteContext.eventKind), qTags = ids, limit = 500))
        }
    }

    private suspend fun subscribeLive(subscriptionId: String, filter: NostrFilter) {
        liveFilters[subscriptionId] = filter
        NostrRepository.subscribe(subscriptionId, filter, readTarget)
    }

    private suspend fun applyReadTarget(urls: Set<String>) {
        readTarget = ChannelRelayPlanner.readTarget(urls, navigationRelayHint)
        // 同じフィルターのまま target だけ変えるため、既存リレーへの REQ は再送されない。
        liveFilters.toList().forEach { (subscriptionId, filter) ->
            NostrRepository.subscribe(subscriptionId, filter, readTarget)
        }
    }

    private fun planRelayContext(recommendedRelays: List<String>): ChannelRelayContext =
        ChannelRelayPlanner.context(recommendedRelays, navigationRelayHint, RelayStore.entries.value)

    /**
     * 実効メタデータの推奨リレーが変わったら、購読先を二段階で切り替える(FR-08、第16.7節)。
     * 新旧両方を購読し、追加リレーの接続または timeout 後に新しい集合へ縮める。
     * 連続更新時は generation が一致しない古い切り替えを中止する。
     */
    private fun updateRelayContext(recommendedRelays: List<String>) {
        val next = planRelayContext(recommendedRelays)
        if (next == relayContext) return
        val previous = relayContext
        relayContext = next
        val generation = ++relayContextGeneration
        val transition = ChannelRelayPlanner.transition(previous, next)
        logRelayContext("transition gen=$generation added=${transition.addedRelays.size}")
        relayTransitionJob?.cancel()
        isRelayTransitioning = transition.needsWarmUp
        syncReadyState()
        relayTransitionJob = launch {
            if (transition.needsWarmUp) {
                applyReadTarget(transition.transitionTargets)
                awaitRelaysConnected(transition.addedRelays)
                if (generation != relayContextGeneration) return@launch
                // 履歴は切り替え前の集合で取得済みのため、追加リレーにだけある最新ページを補う。
                supplementHistoryFrom(transition.addedRelays)
                if (generation != relayContextGeneration) return@launch
            }
            applyReadTarget(transition.finalTargets)
            if (generation == relayContextGeneration) {
                isRelayTransitioning = false
                syncReadyState()
            }
        }
    }

    private suspend fun supplementHistoryFrom(relayUrls: Set<String>) {
        val events = mutableListOf<NostrEvent>()
        fetchChannelEvents(
            SubscriptionSpec(
                id = "$histSubId-${requestSequence++}",
                filters = listOf(
                    NostrFilter(kinds = listOf(42), eTags = listOf(channelId), limit = ChannelHistory.PAGE_SIZE),
                ),
                target = RelayTarget.Explicit(relayUrls),
                behavior = SubscriptionBehavior.Fetch(10_000),
            ),
            settleAfterFirstEoseMillis = HISTORY_SETTLE_MS,
        ) { event -> if (noteContext.matches(event) && event.id !in locallyDeletedMessageIds) events += event }
        history.supplement(events)
    }

    /** 接続失敗しても古いリレーを残し続けないよう、timeout 後は完了扱いにする。 */
    private suspend fun awaitRelaysConnected(urls: Set<String>) {
        withTimeoutOrNull(RELAY_TRANSITION_TIMEOUT_MS) {
            NostrRepository.relayConnectionStates.first { states ->
                urls.all { states[it] == RelayConnectionState.Connected }
            }
        }
    }

    private fun logRelayContext(reason: String) {
        networkTraceLog {
            "[ChannelController] relayContext $reason channel=${channelId.take(8)} " +
                "recommended=${relayContext.recommendedRelays.size} read=${relayContext.readRelays.size} " +
                "write=${relayContext.writeRelays.size}"
        }
    }

    fun close() {
        relayTransitionJob?.cancel()
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
        private const val RELAY_TRANSITION_TIMEOUT_MS = 3_000L
        private const val HISTORY_SETTLE_MS = 1_500L
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

internal data class CachedMetadataSource(val ownerPubkey: String, val eventId: String, val createdAt: Long)

/**
 * ネットワークで解決した実効メタデータより、端末に保存済みのものが `(createdAt, id)` で新しいか。
 * 所有者が一致しない保存値は信用しない。
 */
internal fun shouldKeepCachedMetadata(
    cached: CachedMetadataSource?,
    ownerPubkey: String,
    resolved: NostrEvent,
): Boolean = cached != null && cached.ownerPubkey == ownerPubkey &&
    ChannelLocalStateStore.isNewer(cached.createdAt, cached.eventId, resolved.createdAt, resolved.id)
