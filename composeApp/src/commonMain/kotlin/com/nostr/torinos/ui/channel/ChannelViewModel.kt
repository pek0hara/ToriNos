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

class ChannelViewModel(
    private val channelId: String,
    private val relayUrl: String? = null,
) : SafeViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val channelMeta: ChannelMeta = ChannelMeta(),
            val messages: List<NostrEvent> = emptyList(),
            val profiles: Map<String, NostrProfile> = emptyMap(),
            val canLoadMore: Boolean = false,
            val keepScrolledToTop: Boolean = true,
            val initialUnreadMessageId: String? = null,
            val initialScrollMessageId: String? = null,
            val draftText: String = "",
            val isPosting: Boolean = false,
            val postError: String? = null,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val shortId = channelId.take(16)
    private val relayKey = relayUrl?.hashCode()?.toString() ?: "all"
    private val metaSubId = "ch-meta-$shortId-$relayKey"
    private val msgSubId = "ch-msg-$shortId-$relayKey"
    private val histSubId = "ch-hist-$shortId-$relayKey"
    private val profSubId = "ch-prof-$shortId-$relayKey"

    private val seenIds = linkedSetOf<String>()
    private val pendingPubkeys = mutableSetOf<String>()
    private var profileBatchJob: Job? = null
    private val jobs = mutableListOf<Job>()

    private var oldestCreatedAt: Long? = null
    private var loadingMore = false
    private var lastBatchCount = 0
    private var receivedEoseCount = 0
    private var expectedEoseCount = 1
    private var currentChannelMeta = ChannelMeta()
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
                relayUrl?.let { ChannelCacheStore.upsertChannel(it, event, meta) }
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
            // リレーから最新ページを取得（DB との差分が自動的にマージされる）
            requestPage(until = null)
        }
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

        // 初回のみライブ購読
        if (until == null) {
            NostrRepository.subscribe(
                msgSubId,
                NostrFilter(kinds = listOf(42), eTags = listOf(channelId)),
                relayUrl = relayUrl,
            )
        }
        NostrRepository.subscribe(
            histSubId,
            NostrFilter(kinds = listOf(42), eTags = listOf(channelId), until = until, limit = PAGE_SIZE),
            relayUrl = relayUrl,
        )
    }

    private fun onPageCompleted() {
        loadingMore = false
        val hasMore = lastBatchCount >= PAGE_SIZE
        _state.value = readyState(canLoadMore = hasMore, keepScrolledToTop = false)
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
    ): UiState.Ready =
        UiState.Ready(
            channelMeta = currentChannelMeta,
            messages = filteredMessages(),
            profiles = currentProfiles,
            canLoadMore = canLoadMore,
            keepScrolledToTop = keepScrolledToTop,
            initialUnreadMessageId = initialUnreadMessageId(),
            initialScrollMessageId = initialScrollMessageId,
        )

    private fun syncReadyState() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(
            channelMeta = currentChannelMeta,
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
        NostrRepository.close(msgSubId)
        NostrRepository.close(histSubId)
        NostrRepository.close(profSubId)
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val MAX_SEEN_IDS = 1000
    }
}
