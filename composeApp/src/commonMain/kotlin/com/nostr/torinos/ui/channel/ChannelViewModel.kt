package com.nostr.torinos.ui.channel

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.loadPublicKey
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.toChannelMeta
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.ChannelCacheStore
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.NgWordStore
import com.nostr.torinos.network.NostrRepository
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChannelViewModel(
    private val channelId: String,
    private val relayUrl: String? = null,
) : SafeViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val channelMeta: ChannelMeta = ChannelMeta(),
            val channelOwnerPubkey: String? = null,
            val messages: List<NostrEvent> = emptyList(),
            val profiles: Map<String, NostrProfile> = emptyMap(),
            val replyCounts: Map<String, Int> = emptyMap(),
            val reactionCounts: Map<String, Int> = emptyMap(),
            val customReactions: Map<String, List<CustomReaction>> = emptyMap(),
            val repostCounts: Map<String, Int> = emptyMap(),
            val likedReactions: Map<String, String> = emptyMap(),
            val repostedEvents: Map<String, String> = emptyMap(),
            val canLoadMore: Boolean = false,
            val draftText: String = "",
            val isPosting: Boolean = false,
            val postError: String? = null,
            val editDialog: EditThreadDialogState? = null,
        ) : UiState
    }

    data class EditThreadDialogState(
        val title: String = "",
        val description: String = "",
        val isSaving: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val shortId = channelId.take(16)
    private val relayKey = relayUrl?.hashCode()?.toString() ?: "all"
    private val metaSubId = "ch-meta-$shortId-$relayKey"
    private val metaUpdateSubId = "ch-meta-update-$shortId-$relayKey"
    private val msgSubId = "ch-msg-$shortId-$relayKey"
    private val histSubId = "ch-hist-$shortId-$relayKey"
    private val profSubId = "ch-prof-$shortId-$relayKey"
    private val replyCountSubId = "ch-reply-count-$shortId-$relayKey"
    private val reactionSubId = "ch-react-$shortId-$relayKey"
    private val repostSubId = "ch-repost-$shortId-$relayKey"

    private val seenIds = linkedSetOf<String>()
    private val seenReplyIds = linkedSetOf<String>()
    private val seenReactionIds = linkedSetOf<String>()
    private val seenRepostIds = linkedSetOf<String>()
    private val watchedEventIds = linkedSetOf<String>()
    private val pendingPubkeys = mutableSetOf<String>()
    private var profileBatchJob: Job? = null
    private var engagementBatchJob: Job? = null
    private var pageTimeoutJob: Job? = null
    private val jobs = mutableListOf<Job>()

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
    private var currentCustomReactions = emptyMap<String, List<CustomReaction>>()
    private var currentRepostCounts = emptyMap<String, Int>()
    private var currentLikedReactions = emptyMap<String, String>()
    private var currentRepostedEvents = emptyMap<String, String>()
    private var ownPubkey: String? = null
    private val noteContext = NoteContext.Channel(channelId)

    init {
        launch { ownPubkey = loadPublicKey() }
        start()
    }

    fun onDraftChange(text: String) {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(draftText = text, postError = null)
    }

    fun sendMessage() {
        val current = _state.value as? UiState.Ready ?: return
        val text = current.draftText.trim()
        if (text.isBlank() || current.isPosting) return

        _state.value = current.copy(isPosting = true, postError = null)
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: run {
                (_state.value as? UiState.Ready)?.let {
                    _state.value = it.copy(isPosting = false, postError = "秘密鍵が設定されていません")
                }
                return@launch
            }
            runCatching {
                val event = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = text,
                    kind = noteContext.eventKind,
                    tags = noteContext.replyTags(replyToId = null, replyToPubkey = null) +
                        listOf(listOf("client", "ToriNos")),
                )
                NostrRepository.publish(event)
            }.onSuccess {
                (_state.value as? UiState.Ready)?.let {
                    _state.value = it.copy(draftText = "", isPosting = false)
                }
            }.onFailure { e ->
                (_state.value as? UiState.Ready)?.let {
                    _state.value = it.copy(isPosting = false, postError = e.message ?: "送信に失敗しました")
                }
            }
        }
    }

    fun react(eventId: String, eventPubkey: String) {
        if (currentLikedReactions.containsKey(eventId)) return
        currentLikedReactions = currentLikedReactions + (eventId to "")
        currentReactionCounts = currentReactionCounts + (eventId to (currentReactionCounts[eventId] ?: 0) + 1)
        syncReadyState()
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
                currentLikedReactions = currentLikedReactions + (eventId to reaction.id)
                syncReadyState()
            }
        }
    }

    fun unreact(eventId: String) {
        val reactionEventId = currentLikedReactions[eventId] ?: return
        currentLikedReactions = currentLikedReactions - eventId
        currentReactionCounts = currentReactionCounts + (eventId to maxOf(0, (currentReactionCounts[eventId] ?: 0) - 1))
        syncReadyState()
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
        if (currentRepostedEvents.containsKey(event.id)) return
        currentRepostedEvents = currentRepostedEvents + (event.id to "")
        currentRepostCounts = currentRepostCounts + (event.id to (currentRepostCounts[event.id] ?: 0) + 1)
        syncReadyState()
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
                currentRepostedEvents = currentRepostedEvents + (event.id to repostEvent.id)
                syncReadyState()
            }
        }
    }

    fun unrepost(eventId: String) {
        val repostEventId = currentRepostedEvents[eventId] ?: return
        currentRepostedEvents = currentRepostedEvents - eventId
        currentRepostCounts = currentRepostCounts + (eventId to maxOf(0, (currentRepostCounts[eventId] ?: 0) - 1))
        syncReadyState()
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
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: run {
                val s = _state.value as? UiState.Ready ?: return@launch
                _state.value = s.copy(
                    editDialog = s.editDialog?.copy(isSaving = false, error = "秘密鍵が設定されていません"),
                )
                return@launch
            }
            val title = dialog.title.trim()
            val description = dialog.description.trim()
            runCatching {
                val content = buildJsonObject {
                    put("name", title)
                    put("about", description)
                    put("picture", currentChannelMeta.picture)
                }.toString()
                val event = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = content,
                    kind = 41,
                    tags = listOf(listOf("e", channelId), listOf("client", "ToriNos")),
                )
                NostrRepository.publish(event)
            }.onSuccess {
                currentChannelMeta = currentChannelMeta.copy(name = title, about = description)
                val s = _state.value as? UiState.Ready ?: return@launch
                _state.value = s.copy(
                    channelMeta = currentChannelMeta,
                    editDialog = null,
                )
            }.onFailure { e ->
                val s = _state.value as? UiState.Ready ?: return@launch
                _state.value = s.copy(
                    editDialog = s.editDialog?.copy(
                        isSaving = false,
                        error = e.message ?: "保存に失敗しました",
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
                val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1) ?: return@collect
                if (targetId !in watchedEventIds) return@collect
                currentReactionCounts = currentReactionCounts + (targetId to (currentReactionCounts[targetId] ?: 0) + 1)
                event.toCustomReaction()?.let { reaction ->
                    currentCustomReactions = currentCustomReactions + (
                        targetId to currentCustomReactions[targetId]
                            .orEmpty()
                            .incrementedWith(reaction)
                    )
                }
                if (ownPubkey != null && event.pubkey == ownPubkey && !currentLikedReactions.containsKey(targetId)) {
                    currentLikedReactions = currentLikedReactions + (targetId to event.id)
                }
                syncReadyState()
                scheduleProfileFetch(event.pubkey)
            }
        }

        jobs += launch {
            NostrRepository.events(repostSubId).collect { event ->
                if (event.kind != 6 || !seenRepostIds.add(event.id)) return@collect
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

        // プロフィール受信
        jobs += launch {
            NostrRepository.events(profSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                pendingPubkeys.remove(event.pubkey)
                currentProfiles = currentProfiles + (event.pubkey to profile)
                syncReadyState()
            }
        }

        // ミュート・NGワード変更時に表示リストを再フィルタ
        jobs += launch {
            MuteStore.mutedPubkeys.collect { syncReadyState() }
        }
        jobs += launch {
            NgWordStore.ngWords.collect { syncReadyState() }
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
        currentMessages = insertSorted(currentMessages, event)
        oldestCreatedAt = currentMessages.lastOrNull()?.createdAt
        if (notify) syncReadyState()
        return 1
    }

    private fun insertSorted(messages: List<NostrEvent>, event: NostrEvent): List<NostrEvent> {
        if (messages.isEmpty()) return listOf(event)
        // 降順（新しい順）: index 0 = 最新、last = 最古
        if (event.createdAt >= messages.first().createdAt) return listOf(event) + messages
        if (event.createdAt <= messages.last().createdAt) return messages + event
        val list = messages.toMutableList()
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].createdAt >= event.createdAt) lo = mid + 1 else hi = mid
        }
        list.add(lo, event)
        return list
    }

    private fun readyState(canLoadMore: Boolean): UiState.Ready =
        UiState.Ready(
            channelMeta = currentChannelMeta,
            channelOwnerPubkey = currentChannelOwnerPubkey,
            messages = filteredMessages(),
            profiles = currentProfiles,
            replyCounts = currentReplyCounts,
            reactionCounts = currentReactionCounts,
            customReactions = currentCustomReactions,
            repostCounts = currentRepostCounts,
            likedReactions = currentLikedReactions,
            repostedEvents = currentRepostedEvents,
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
            customReactions = currentCustomReactions,
            repostCounts = currentRepostCounts,
            likedReactions = currentLikedReactions,
            repostedEvents = currentRepostedEvents,
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
        val muted = MuteStore.mutedPubkeys.value
        val ngWords = NgWordStore.ngWords.value
        return currentMessages.filter { msg ->
            !muted.contains(msg.pubkey) &&
                (ngWords.isEmpty() || ngWords.none { msg.content.contains(it, ignoreCase = true) })
        }
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in currentProfiles || pubkey in pendingPubkeys) return
        pendingPubkeys.add(pubkey)
        profileBatchJob?.cancel()
        profileBatchJob = launch {
            delay(500)
            if (pendingPubkeys.isEmpty()) return@launch
            val authors = pendingPubkeys.toList()
            pendingPubkeys.removeAll(authors)
            NostrRepository.subscribe(
                profSubId,
                NostrFilter(kinds = listOf(0), authors = authors),
                relayUrl = relayUrl,
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
        }
    }

    override fun onCleared() {
        super.onCleared()
        jobs.forEach { it.cancel() }
        profileBatchJob?.cancel()
        engagementBatchJob?.cancel()
        pageTimeoutJob?.cancel()
        NostrRepository.close(metaSubId)
        NostrRepository.close(metaUpdateSubId)
        NostrRepository.close(msgSubId)
        NostrRepository.close(histSubId)
        NostrRepository.close(profSubId)
        NostrRepository.close(replyCountSubId)
        NostrRepository.close(reactionSubId)
        NostrRepository.close(repostSubId)
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val MAX_SEEN_IDS = 1000
        private const val MAX_WATCHED_EVENTS = 100
    }
}
