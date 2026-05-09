package com.nostr.torinos.ui.channel

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.toChannelMeta
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
            val canLoadMore: Boolean = false,
            val keepScrolledToTop: Boolean = true,
            val initialUnreadMessageId: String? = null,
            val initialScrollMessageId: String? = null,
            val scrollToBottomRequest: Boolean = false,
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

    private val seenIds = linkedSetOf<String>()
    private val pendingPubkeys = mutableSetOf<String>()
    private var profileBatchJob: Job? = null
    private val jobs = mutableListOf<Job>()

    private var oldestCreatedAt: Long? = null
    private var loadingMore = false
    private var isInitialDiffFetch = false
    private var lastBatchCount = 0
    private var receivedEoseCount = 0
    private var expectedEoseCount = 1
    private var currentChannelMeta = ChannelMeta()
    private var currentChannelOwnerPubkey: String? = null
    private var latestMetaUpdateCreatedAt = -1L
    private var currentMessages = emptyList<NostrEvent>()
    private var currentProfiles = emptyMap<String, NostrProfile>()
    private var initialLastReadAt: Long? = null
    private var initialScrollMessageId: String? = null

    init {
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
                    kind = 42,
                    tags = listOf(listOf("e", channelId, "", "root"), listOf("client", "ToriNos")),
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

    fun onScrolledToLatest() {
        markLatestRead()
        saveLatestAsScrollPosition()
    }

    fun onScrollToBottomConsumed() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(scrollToBottomRequest = false)
    }

    fun saveScrollPosition(messageId: String) {
        val cacheRelayUrl = relayUrl ?: return
        launch {
            ChannelCacheStore.saveScrollPosition(cacheRelayUrl, channelId, messageId)
        }
    }

    private fun saveLatestAsScrollPosition() {
        val cacheRelayUrl = relayUrl ?: return
        val latestId = currentMessages.maxByOrNull { it.createdAt }?.id ?: return
        launch {
            ChannelCacheStore.saveScrollPosition(cacheRelayUrl, channelId, latestId)
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
                if (event.kind != 42) return@collect
                appendMessage(event)
                relayUrl?.let { ChannelCacheStore.upsertMessage(it, event, channelId) }
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
            }
        }

        // kind:42 メッセージ受信（過去ページ）
        jobs += launch {
            NostrRepository.events(histSubId).collect { event ->
                if (event.kind != 42) return@collect
                val added = appendMessage(event)
                lastBatchCount += added
                relayUrl?.let { ChannelCacheStore.upsertMessage(it, event, channelId) }
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
            }
        }

        // リレーが msgSubId を CLOSED したら再購読
        jobs += launch {
            NostrRepository.closed(msgSubId).collect {
                val sinceTs = currentMessages.maxOfOrNull { it.createdAt }
                    ?: Clock.System.now().epochSeconds
                NostrRepository.subscribe(
                    msgSubId,
                    NostrFilter(kinds = listOf(42), eTags = listOf(channelId), since = sinceTs),
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
                _state.value = readyState(canLoadMore = false, keepScrolledToTop = true)
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
                initialLastReadAt = ChannelCacheStore.getLastReadAt(cacheRelayUrl, channelId)
                initialScrollMessageId = ChannelCacheStore.getScrollPosition(cacheRelayUrl, channelId)
                // DB からキャッシュ済みメッセージを先に読み込んで即時表示
                val cached = ChannelCacheStore.getMessages(cacheRelayUrl, channelId)
                if (cached.isNotEmpty()) {
                    cached.forEach { appendMessage(it) }
                    _state.value = readyState(canLoadMore = false, keepScrolledToTop = false)
                }
            }
            // チャンネルメタ取得
            NostrRepository.subscribe(metaSubId, NostrFilter(ids = listOf(channelId)), relayUrl = relayUrl)
            // 初回取得: DB に何かあれば最新以降の差分のみ、無ければ最新 PAGE_SIZE
            startInitialFetch(cacheLatest = currentMessages.lastOrNull()?.createdAt)
        }
    }

    private suspend fun startInitialFetch(cacheLatest: Long?) {
        isInitialDiffFetch = cacheLatest != null
        loadingMore = true
        lastBatchCount = 0
        receivedEoseCount = 0
        expectedEoseCount = if (relayUrl != null) 1 else NostrRepository.relayCount.coerceAtLeast(1)
        val current = _state.value as? UiState.Ready
        if (current != null) {
            _state.value = current.copy(canLoadMore = false)
        }

        // ライブ購読: 起動時刻以降の新着のみ受信（リレー default cap 分の過去履歴を流させない）
        NostrRepository.subscribe(
            msgSubId,
            NostrFilter(
                kinds = listOf(42),
                eTags = listOf(channelId),
                since = Clock.System.now().epochSeconds,
            ),
            relayUrl = relayUrl,
        )

        // 履歴ページ: キャッシュがあれば最新 createdAt 以降の差分のみ、無ければ最新 PAGE_SIZE 件
        val histFilter = if (cacheLatest != null) {
            NostrFilter(kinds = listOf(42), eTags = listOf(channelId), since = cacheLatest + 1)
        } else {
            NostrFilter(kinds = listOf(42), eTags = listOf(channelId), until = null, limit = PAGE_SIZE)
        }
        NostrRepository.subscribe(histSubId, histFilter, relayUrl = relayUrl)
    }

    private suspend fun requestPage(until: Long?) {
        loadingMore = true
        lastBatchCount = 0
        receivedEoseCount = 0
        expectedEoseCount = if (relayUrl != null) 1 else NostrRepository.relayCount.coerceAtLeast(1)
        val current = _state.value as? UiState.Ready
        if (current != null) {
            _state.value = current.copy(canLoadMore = false)
        }
        NostrRepository.subscribe(
            histSubId,
            NostrFilter(kinds = listOf(42), eTags = listOf(channelId), until = until, limit = PAGE_SIZE),
            relayUrl = relayUrl,
        )
    }

    private fun onPageCompleted() {
        loadingMore = false
        // 初回差分取得時は受信件数が 0 でも古い方向にキャッシュがあるので true、それ以外は従来の閾値判定
        val hasMore = if (isInitialDiffFetch) true else lastBatchCount >= PAGE_SIZE
        isInitialDiffFetch = false
        _state.value = readyState(
            canLoadMore = hasMore,
            keepScrolledToTop = false,
            scrollToBottomRequest = initialUnreadMessageId() == null,
        )
        markLatestRead()
        NostrRepository.close(histSubId)
    }

    private fun appendMessage(event: NostrEvent): Int {
        if (!seenIds.add(event.id)) return 0
        while (seenIds.size > MAX_SEEN_IDS) seenIds.remove(seenIds.first())
        if (currentMessages.any { it.id == event.id }) return 0
        currentMessages = (currentMessages + event).sortedBy { it.createdAt }
        oldestCreatedAt = currentMessages.firstOrNull()?.createdAt
        syncReadyState()
        return 1
    }

    private fun readyState(
        canLoadMore: Boolean,
        keepScrolledToTop: Boolean,
        scrollToBottomRequest: Boolean = false,
    ): UiState.Ready =
        UiState.Ready(
            channelMeta = currentChannelMeta,
            channelOwnerPubkey = currentChannelOwnerPubkey,
            messages = filteredMessages(),
            profiles = currentProfiles,
            canLoadMore = canLoadMore,
            keepScrolledToTop = keepScrolledToTop,
            initialUnreadMessageId = initialUnreadMessageId(),
            initialScrollMessageId = initialScrollMessageId,
            scrollToBottomRequest = scrollToBottomRequest,
        )

    private fun syncReadyState() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(
            channelMeta = currentChannelMeta,
            channelOwnerPubkey = currentChannelOwnerPubkey,
            messages = filteredMessages(),
            profiles = currentProfiles,
            initialUnreadMessageId = initialUnreadMessageId(),
            initialScrollMessageId = initialScrollMessageId,
        )
    }

    private fun initialUnreadMessageId(): String? {
        val lastReadAt = initialLastReadAt ?: return null
        return filteredMessages().firstOrNull { it.createdAt > lastReadAt }?.id
    }

    private fun markLatestRead() {
        val cacheRelayUrl = relayUrl ?: return
        val latestReadAt = currentMessages.maxOfOrNull { it.createdAt } ?: return
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

    override fun onCleared() {
        super.onCleared()
        jobs.forEach { it.cancel() }
        profileBatchJob?.cancel()
        NostrRepository.close(metaSubId)
        NostrRepository.close(metaUpdateSubId)
        NostrRepository.close(msgSubId)
        NostrRepository.close(histSubId)
        NostrRepository.close(profSubId)
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val MAX_SEEN_IDS = 1000
    }
}
