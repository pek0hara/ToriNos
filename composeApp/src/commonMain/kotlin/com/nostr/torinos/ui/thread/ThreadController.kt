package com.nostr.torinos.ui.thread

import com.nostr.torinos.model.isLikeReaction
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.engagement.EngagementAction
import com.nostr.torinos.engagement.EngagementOperationId
import com.nostr.torinos.engagement.EngagementReducer
import com.nostr.torinos.ui.feed.EngagementFetcher
import com.nostr.torinos.ui.feed.SharedEngagementFetchers
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.EngagementStore
import com.nostr.torinos.engagement.NoteEngagementCommand
import com.nostr.torinos.engagement.PendingEngagementOperation
import com.nostr.torinos.engagement.PendingEngagementRegistry
import com.nostr.torinos.engagement.TargetEngagement
import com.nostr.torinos.engagement.hasOwnReaction
import com.nostr.torinos.engagement.isRepostedByMe
import com.nostr.torinos.engagement.summarizeEngagement
import com.nostr.torinos.engagement.toNoteEngagementState
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.engagement.NoteTarget
import com.nostr.torinos.engagement.ReactionEventReducer
import com.nostr.torinos.engagement.ReactionRefreshFetcher
import com.nostr.torinos.engagement.displayOwnEmojiReactionEventIds
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.COMMENT_EVENT_KIND
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
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.ui.channel.channelThreadReadTarget
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.resolveReplyTarget
import com.nostr.torinos.ui.SafeCoroutineLauncher
import com.nostr.torinos.ui.timeline.ProfileHydrator
import com.nostr.torinos.ui.timeline.NoteEngagementCoordinator
import com.nostr.torinos.util.engagementTraceLog
import com.nostr.torinos.ui.timeline.NoteDeletionResult
import com.nostr.torinos.ui.timeline.NoteDeletionService
import com.nostr.torinos.ui.timeline.QuoteResolver
import com.nostr.torinos.ui.thread.ThreadViewModel.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal class ThreadController(
    private val eventId: String,
    private val noteContext: NoteContext = NoteContext.Timeline,
    private val accountSession: AccountSession? = null,
    private val scope: CoroutineScope,
    private val autoStart: Boolean = true,
    /** 付随情報のイベント集合。フィードと共有する。 */
    private val engagementStore: EngagementStore = EngagementStore.shared,
) {
    private val safeCoroutineLauncher = SafeCoroutineLauncher(scope, "ThreadController")
    private fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        safeCoroutineLauncher.launch(block = block)

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

    private var channelReadRelays: Set<String>? = if (noteContext is NoteContext.Channel) emptySet() else null
    private val subscriptionJobs = mutableListOf<Job>()
    private val lifecycleJobs = mutableListOf<Job>()
    private val seenReplyIds = linkedSetOf<String>()
    private val seenReplyCountIds = linkedSetOf<String>()
    private val seenQuoteRepostIds = linkedSetOf<String>()
    /** 付随情報を表示する投稿（表示中の投稿・直接の返信・表示中の投稿の引用）。 */
    private val engagementTargets = linkedSetOf<String>()
    private val requestedEngagementPubkeys = mutableSetOf<String>()
    /** 送信中の操作。アカウントで共有し、フィードにも同じ表示が出る。 */
    private val pendingOperations = PendingEngagementRegistry.forSession(accountSession?.sessionId)
    /** 通常の投稿は、フィードと共有する取得を使う。同じ投稿をフィードが表示していても重ねて取りにいかない。 */
    private val sharedFetcher: EngagementFetcher? =
        if (noteContext == NoteContext.Timeline) SharedEngagementFetchers.forTarget(RelayTarget.AllEnabled) else null
    private val watchedEventIds = linkedSetOf<String>()
    private val watchedReactionEventIds = linkedSetOf<String>()
    private val watchedRepostEventIds = linkedSetOf<String>()
    private val pendingQuotedEventIds = linkedSetOf<String>()
    private var replyCountBatchJob: Job? = null
    private var reactionBatchJob: Job? = null
    private var repostBatchJob: Job? = null
    private var started = false
    private var ownPubkey: String? = null
    private val engagementCoordinator = NoteEngagementCoordinator(accountSession?.signer)
    private val replyPublisher = ReplyPublisher(accountSession?.signer)
    private val noteDeletionService = NoteDeletionService(accountSession?.signer, accountSession?.sessionId)
    private val quoteResolver = QuoteResolver("thread-quote-$shortId")
    private val profileHydrator = ProfileHydrator(
        scope,
        ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS),
    )
    private var nextEngagementOperationId = 0L
    private var nextReactionRefreshId = 0L

    init {
        sharedFetcher?.register(
            owner = this,
            // 表示中の投稿と返信は、フィードの新着より優先してライブ購読に入れる。
            sortTimeOf = { if (it in engagementTargets) Long.MAX_VALUE else null },
            onEvent = { event -> scheduleProfileFetch(event.pubkey) },
        )
        if (isWriteSupported) {
            launch {
                ownPubkey = accountSession?.pubkey
                projectEngagement(engagementTargets.toList())
            }
        }
        lifecycleJobs += launch {
            engagementStore.changes.collect { changed -> projectEngagement(changed.filter { it in engagementTargets }) }
        }
        lifecycleJobs += launch {
            pendingOperations.changes.collect { changed -> projectEngagement(changed.filter { it in engagementTargets }) }
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

    fun deleteRoot() {
        val root = _state.value.root ?: run {
            _state.value = _state.value.copy(deleteError = "投稿が読み込まれていません")
            return
        }
        if (_state.value.isDeleting) return

        _state.value = _state.value.copy(isDeleting = true, deleteError = null)
        launch {
            when (val result = noteDeletionService.delete(root)) {
                NoteDeletionResult.Deleted -> {
                    _state.value = _state.value.copy(
                        root = null,
                        isLoading = false,
                        isDeleting = false,
                        deleteError = null,
                        deleteCompletedCount = _state.value.deleteCompletedCount + 1,
                    )
                }
                NoteDeletionResult.MissingSigner -> {
                    _state.value = _state.value.copy(
                        isDeleting = false,
                        deleteError = "秘密鍵が設定されていません",
                    )
                }
                NoteDeletionResult.NotOwner -> {
                    _state.value = _state.value.copy(
                        isDeleting = false,
                        deleteError = "自分の投稿だけ削除できます",
                    )
                }
                is NoteDeletionResult.Failed -> {
                    _state.value = _state.value.copy(
                        isDeleting = false,
                        deleteError = result.cause.message ?: "投稿の削除要求を送信できませんでした",
                    )
                }
            }
        }
    }

    /** ルート以外の返信（チャンネルの kind:42 を含む）を削除する。ルート削除と違い画面は閉じない。 */
    fun deleteReply(eventId: String) {
        val reply = _state.value.replies.firstOrNull { it.id == eventId } ?: run {
            _state.value = _state.value.copy(deleteError = "投稿が読み込まれていません")
            return
        }
        val rootId = _state.value.root?.id ?: return
        if (_state.value.isDeleting) return

        _state.value = _state.value.copy(isDeleting = true, deleteError = null)
        launch {
            when (val result = noteDeletionService.delete(reply)) {
                NoteDeletionResult.Deleted -> {
                    val tree = ThreadTreeReducer.reduce(
                        _state.value.threadTreeState(),
                        ThreadTreeAction.ReplyRemoved(rootId, eventId),
                    )
                    _state.value = _state.value.withThreadTree(tree).copy(
                        isDeleting = false,
                        deleteError = null,
                        replyDeleteCompletedCount = _state.value.replyDeleteCompletedCount + 1,
                    )
                }
                NoteDeletionResult.MissingSigner -> {
                    _state.value = _state.value.copy(
                        isDeleting = false,
                        deleteError = "秘密鍵が設定されていません",
                    )
                }
                NoteDeletionResult.NotOwner -> {
                    _state.value = _state.value.copy(
                        isDeleting = false,
                        deleteError = "自分の投稿だけ削除できます",
                    )
                }
                is NoteDeletionResult.Failed -> {
                    _state.value = _state.value.copy(
                        isDeleting = false,
                        deleteError = result.cause.message ?: "投稿の削除要求を送信できませんでした",
                    )
                }
            }
        }
    }

    fun submitReply() {
        val root = _state.value.root ?: return
        val text = _state.value.replyText.trim()
        if (text.isBlank()) return
        _state.value = _state.value.copy(isReplying = true, replyError = null)
        launch {
            val target = resolveReplyTarget(root, noteContext) ?: run {
                _state.value = _state.value.copy(
                    isReplying = false,
                    replyError = "返信元のスレッド情報を取得できませんでした",
                )
                return@launch
            }
            when (
                val result = replyPublisher.publish(
                    ReplyCommand(
                        content = text,
                        target = target,
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

    fun unrepost(targetEventId: String) {
        val repostEventId = if (targetEventId == eventId) {
            _state.value.ownRepostEventId
        } else {
            _state.value.repostedEvents[targetEventId]
        } ?: return
        runEngagementOperation(
            targetEventId,
            EngagementRequest.RemoveRepost,
            NoteEngagementCommand.RemoveRepost(repostEventId),
            "リポストの解除に失敗しました",
        )
    }

    fun refreshReactions(targetEventId: String) {
        if (targetEventId !in watchedReactionEventIds) return
        launch {
            ReactionRefreshFetcher.fetch(
                subscriptionId = "thread-reaction-refresh-$shortId-${++nextReactionRefreshId}",
                eventId = targetEventId,
            ).forEach { engagementStore.observe(it, null) }
        }
    }

    private fun runEngagementOperation(
        targetEventId: String,
        request: EngagementRequest,
        command: NoteEngagementCommand,
        failureMessage: String,
    ) {
        ownPubkey?.let(::ensureProfileAvailable)
        val operationId = EngagementOperationId("thread-$shortId-${++nextEngagementOperationId}")
        val before = _state.value.noteEngagement(targetEventId, eventId)
        val optimistic = engagementCoordinator.begin(before, operationId, request)
        if (optimistic == before) return
        optimistic.pendingOperations[request.slot]?.let { pendingOperations.put(targetEventId, request.slot, it.copy(deliveryRelayUrls = com.nostr.torinos.network.RelayStore.writableRelayUrlsSnapshot().toSet())) }
        _state.value = _state.value.withEngagement(targetEventId, eventId, optimistic, ownPubkey)
            .copy(engagementError = null)
        launch {
            var failure: Throwable? = null
            try {
                val published = engagementCoordinator.execute(command).getOrThrow()
                // 送ったイベント（取り消しは kind 5）をストアへ入れてから送信中の操作を外す。
                engagementStore.observeLocal(published, accountSession?.sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            } finally {
                pendingOperations.remove(targetEventId, operationId)
                projectEngagement(listOf(targetEventId))
            }
            if (failure != null) _state.value = _state.value.copy(engagementError = failureMessage)
        }
    }

    fun startSubscriptions() {
        if (started) return
        started = true
        sharedFetcher?.activate(this)

        scheduleReplyCountFetch(eventId)
        scheduleReactionFetch(eventId)
        scheduleRepostFetch(eventId)

        subscriptionJobs += launch {
            subscribe(rootSubId, NostrFilter(ids = listOf(eventId), kinds = noteContext.readableEventKinds, limit = 1))
            subscribe(repliesSubId, replyFilters(listOf(eventId), limit = 100))
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
                    subscribe(
                        replyParentSubId,
                        NostrFilter(ids = listOf(parentId), kinds = noteContext.readableEventKinds, limit = 1),
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
                val tree = ThreadTreeReducer.reduce(
                    _state.value.threadTreeState(),
                    ThreadTreeAction.DirectReplyReceived(event),
                )
                _state.value = _state.value.withThreadTree(tree).copy(isLoading = false)
                scheduleReplyCountFetch(event.id)
                scheduleReactionFetch(event.id)
                scheduleRepostFetch(event.id)
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
                // 件数はストアから導出する。リポジトリが受信時にストアへ入れている。
                if (event.kind == 7) scheduleProfileFetch(event.pubkey)
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(repostSubId).collect { event ->
                if (event.kind in listOf(6, 16)) scheduleProfileFetch(event.pubkey)
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(quoteRepostSubId).collect { event ->
                if (!noteContext.matches(event)) return@collect
                val targetIds = event.tags
                    .filter { it.firstOrNull() == "q" }
                    .mapNotNull { it.getOrNull(1) }
                    .filter { it in watchedRepostEventIds }
                    .distinct()
                    .filter { seenQuoteRepostIds.add("${event.id}:$it") }
                if (targetIds.isEmpty()) return@collect
                // 件数と引用の一覧はストアから導出する。ここでは引用自体の付随情報の取得を始める。
                val quotesRoot = eventId in targetIds
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
                if (quotesRoot) {
                    scheduleReplyCountFetch(event.id)
                    scheduleReactionFetch(event.id)
                    scheduleRepostFetch(event.id)
                }
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
        sharedFetcher?.deactivate(this)
        subscriptionJobs.forEach { it.cancel() }
        subscriptionJobs.clear()
        replyCountBatchJob?.cancel()
        reactionBatchJob?.cancel()
        repostBatchJob?.cancel()
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
                    kinds = noteContext.readableEventKinds,
                    onEvent = { event ->
                        val cur = _state.value
                        if (!cur.quotedEvents.containsKey(event.id)) {
                            _state.value = cur.copy(
                                quotedEvents = cur.quotedEvents + (event.id to event),
                            )
                            scheduleProfileFetch(event.pubkey)
                            scheduleMentionedProfileFetch(event.content)
                        }
                    },
                )
                if (resolution.events.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        quotedEvents = _state.value.quotedEvents + resolution.events,
                    )
                }
            } finally {
                pendingQuotedEventIds.removeAll(missingIds.toSet())
            }
        }
    }

    fun close() {
        stopSubscriptions()
        sharedFetcher?.release(this)
        engagementStore.release(this)
        lifecycleJobs.forEach { it.cancel() }
        lifecycleJobs.clear()
        profileHydrator.close()
    }

    /**
     * 表示する投稿の付随情報を、ストアの集合と送信中の操作から作り直す。
     * 表示中の投稿は、リアクション・リポストした人の一覧と引用の一覧も作る。
     */
    private fun projectEngagement(targetIds: Collection<String>) {
        if (targetIds.isEmpty()) return
        val own = ownPubkey
        var next = _state.value
        val newQuotes = mutableListOf<String>()
        val newPubkeys = linkedSetOf<String>()
        targetIds.distinct().forEach { targetId ->
            val engagement = engagementStore.snapshot(targetId) ?: TargetEngagement()
            next = next.withProjectedEngagement(targetId, eventId, engagement, own, pendingOperations.operations(targetId), noteContext, channelReadRelays)
            if (targetId == eventId) newQuotes += engagement.quotes.keys.filter { it !in engagementTargets }
            // 他の画面が先に受信したイベントは、この画面の購読には届かない。一覧に出す人のプロフィールをここで求める。
            (engagement.reactions.values + engagement.reposts.values + engagement.quotes.values)
                .mapNotNullTo(newPubkeys) { event -> event.pubkey.takeIf(requestedEngagementPubkeys::add) }
        }
        _state.value = next
        // 他の画面が先に受信したイベントは、この画面の購読には届かない。一覧に出す人のプロフィールをここで求める。
        // 画面を開いた直後はプロフィールの更新通知をまだ受け取れないため、キャッシュにあるものは直接入れる。
        newPubkeys.forEach(::ensureProfileAvailable)
        // 表示中の投稿を引用した投稿は、引用カードに出す付随情報も取る。
        if (sharedFetcher != null) {
            newQuotes.forEach { quoteId ->
                scheduleReplyCountFetch(quoteId)
                scheduleReactionFetch(quoteId)
            }
        }
    }

    private fun trackEngagement(targetId: String) {
        if (!engagementTargets.add(targetId)) return
        engagementStore.retain(this, engagementTargets.toSet())
        sharedFetcher?.track(this, targetId)
        projectEngagement(listOf(targetId))
    }

    private fun scheduleProfileFetch(pubkey: String) {
        profileHydrator.request(setOf(pubkey))
    }

    private fun ensureProfileAvailable(pubkey: String) {
        ProfileRepository.getCached(pubkey)?.let { profile ->
            _state.value = _state.value.copy(profiles = _state.value.profiles + (pubkey to profile))
        }
        scheduleProfileFetch(pubkey)
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
        scheduleRepostFetch(event.id)
    }

    private fun scheduleReplyCountFetch(eventId: String) {
        if (!watchedEventIds.add(eventId)) return
        while (watchedEventIds.size > MAX_WATCHED_EVENTS) watchedEventIds.remove(watchedEventIds.first())
        replyCountBatchJob?.cancel()
        replyCountBatchJob = launch {
            delay(300)
            subscribe(
                replyCountSubId,
                replyFilters(watchedEventIds.toList()),
            )
        }
    }

    private fun scheduleReactionFetch(targetId: String) {
        trackEngagement(targetId)
        // 通常の投稿は共有の取得（フィードと同じ）で取る。チャンネルはチャンネルのリレーから個別に取る。
        if (sharedFetcher != null) return
        if (!watchedReactionEventIds.add(targetId)) return
        while (watchedReactionEventIds.size > MAX_WATCHED_EVENTS) {
            watchedReactionEventIds.remove(watchedReactionEventIds.first())
        }
        reactionBatchJob?.cancel()
        reactionBatchJob = launch {
            delay(300)
            subscribe(
                reactionSubId,
                threadEngagementFilters(eventId, watchedReactionEventIds.toList()) { ids ->
                    NostrFilter(kinds = listOf(7), eTags = ids, limit = 500)
                },
            )
        }
    }

    private fun scheduleRepostFetch(targetId: String) {
        if (sharedFetcher != null) return
        if (!watchedRepostEventIds.add(targetId)) return
        while (watchedRepostEventIds.size > MAX_WATCHED_EVENTS) {
            watchedRepostEventIds.remove(watchedRepostEventIds.first())
        }
        repostBatchJob?.cancel()
        repostBatchJob = launch {
            delay(300)
            val ids = watchedRepostEventIds.toList()
            subscribe(
                repostSubId,
                threadEngagementFilters(eventId, ids) { targetIds ->
                    NostrFilter(kinds = listOf(6, 16), eTags = targetIds, limit = 500)
                },
            )
            subscribe(
                quoteRepostSubId,
                threadEngagementFilters(eventId, ids) { targetIds ->
                    NostrFilter(kinds = noteContext.readableEventKinds, qTags = targetIds, limit = 500)
                },
            )
        }
    }

    private suspend fun subscribe(subscriptionId: String, filter: NostrFilter): Set<String> =
        subscribe(subscriptionId, listOf(filter))

    private suspend fun subscribe(subscriptionId: String, filters: List<NostrFilter>): Set<String> {
        val target = (noteContext as? NoteContext.Channel)?.let {
            channelThreadReadTarget(it.channelId)
        } ?: RelayTarget.AllEnabled
        val relays = NostrRepository.subscribe(subscriptionId, filters, target)
        if (noteContext is NoteContext.Channel && channelReadRelays != relays) {
            channelReadRelays = relays
            projectEngagement(engagementTargets.toList())
        }
        return relays
    }

    private fun replyFilters(ids: List<String>, limit: Int? = null): List<NostrFilter> =
        if (noteContext == NoteContext.Timeline) {
            listOf(
                NostrFilter(kinds = listOf(1), eTags = ids, limit = limit),
                NostrFilter(
                    kinds = listOf(COMMENT_EVENT_KIND),
                    rootKindTags = listOf("1"),
                    eTags = ids,
                    limit = limit,
                ),
                NostrFilter(
                    kinds = listOf(COMMENT_EVENT_KIND),
                    rootKindTags = listOf("1"),
                    rootEventTags = ids,
                    limit = limit,
                ),
            )
        } else {
            listOf(NostrFilter(kinds = listOf(noteContext.eventKind), eTags = ids, limit = limit))
        }

    companion object {
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
    repostCount = if (eventId == rootEventId) repostCount else repostCounts[eventId] ?: 0,
    ownRepostEventId = if (eventId == rootEventId) ownRepostEventId else repostedEvents[eventId],
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
        repostCounts = if (isRoot) repostCounts else repostCounts + (eventId to engagement.repostCount),
        repostedEvents = if (isRoot) repostedEvents else
            repostedEvents.putOrRemove(eventId, engagement.ownRepostEventId),
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

/**
 * ストアの集合から導出した値に送信中の操作を重ねて反映する。表示中の投稿なら、
 * リアクション・リポストした人の一覧（新しい順・1人1件）と引用の一覧も作る。
 */
internal fun ThreadViewModel.UiState.withProjectedEngagement(
    eventId: String,
    rootEventId: String,
    engagement: TargetEngagement,
    ownPubkey: String?,
    pending: Map<EngagementSlot, PendingEngagementOperation>,
    noteContext: NoteContext = NoteContext.Timeline,
    relayScope: Set<String>? = null,
): ThreadViewModel.UiState {
    val projected = com.nostr.torinos.engagement.projectEngagement(
        engagement, ownPubkey, com.nostr.torinos.engagement.EngagementViewScope(noteContext, relayScope), pending,
    )
    val scoped = projected.events
    val display = projected.state
    val reactions = scoped.reactions.values.sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
    var next = withEngagement(eventId, rootEventId, display, ownPubkey = null).copy(
        reactionEvents = reactionEvents.putListOrRemove(eventId, reactions),
    )
    if (eventId == rootEventId) {
        fun List<String>.withOwn(include: Boolean) = when {
            ownPubkey == null -> this
            include && ownPubkey !in this -> listOf(ownPubkey) + this
            !include -> this - ownPubkey
            else -> this
        }
        next = next.copy(
            reactionPubkeys = reactions.reversed().map { it.pubkey }.distinct().withOwn(display.hasOwnReaction),
            repostPubkeys = scoped.reposts.values.sortedByDescending { it.createdAt }.map { it.pubkey }.distinct()
                .withOwn(display.isRepostedByMe),
            quoteReposts = scoped.quotes.values.sortedByDescending { it.createdAt },
        )
    }
    return next
}

private fun <K, V> Map<K, V>.putOrRemove(key: K, value: V?): Map<K, V> =
    if (value == null) this - key else this + (key to value)

private fun <K, V> Map<K, List<V>>.putListOrRemove(key: K, value: List<V>): Map<K, List<V>> =
    if (value.isEmpty()) this - key else this + (key to value)

private fun <K, K2, V2> Map<K, Map<K2, V2>>.putMapOrRemove(
    key: K,
    value: Map<K2, V2>,
): Map<K, Map<K2, V2>> = if (value.isEmpty()) this - key else this + (key to value)

/**
 * 表示中の投稿と返信への付随情報を取るフィルター。limit はフィルターごとに効くので、
 * 返信へのリアクションなどで上限が埋まって表示中の投稿の分が欠けないよう、表示中の投稿を分ける。
 */
internal fun threadEngagementFilters(
    rootId: String,
    ids: List<String>,
    build: (List<String>) -> NostrFilter,
): List<NostrFilter> {
    val others = ids.filter { it != rootId }
    return listOf(build(listOf(rootId))) + if (others.isEmpty()) emptyList() else listOf(build(others))
}
