package com.example.nostr.ui.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nostr.model.ChannelMeta
import com.example.nostr.model.NostrEvent
import com.example.nostr.model.NostrFilter
import com.example.nostr.model.NostrProfile
import com.example.nostr.model.toChannelMeta
import com.example.nostr.model.toProfile
import com.example.nostr.network.NostrRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChannelViewModel(private val channelId: String) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val channelMeta: ChannelMeta = ChannelMeta(),
            val messages: List<NostrEvent> = emptyList(),
            val profiles: Map<String, NostrProfile> = emptyMap(),
            val canLoadMore: Boolean = false,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val shortId = channelId.take(16)
    private val metaSubId = "ch-meta-$shortId"
    private val msgSubId = "ch-msg-$shortId"
    private val histSubId = "ch-hist-$shortId"
    private val profSubId = "ch-prof-$shortId"

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

    init {
        start()
    }

    fun loadMore() {
        if (loadingMore || (_state.value as? UiState.Ready)?.canLoadMore != true) return
        viewModelScope.launch {
            requestPage(until = oldestCreatedAt?.minus(1))
        }
    }

    private fun start() {
        // kind:40 でチャンネルメタ取得
        jobs += viewModelScope.launch {
            NostrRepository.events(metaSubId).collect { event ->
                if (event.kind != 40) return@collect
                val meta = event.toChannelMeta() ?: return@collect
                currentChannelMeta = meta
                syncReadyState()
            }
        }

        // kind:42 メッセージ受信（ライブ）
        jobs += viewModelScope.launch {
            NostrRepository.events(msgSubId).collect { event ->
                if (event.kind != 42) return@collect
                appendMessage(event)
                scheduleProfileFetch(event.pubkey)
            }
        }

        // kind:42 メッセージ受信（過去ページ）
        jobs += viewModelScope.launch {
            NostrRepository.events(histSubId).collect { event ->
                if (event.kind != 42) return@collect
                val added = appendMessage(event)
                lastBatchCount += added
                scheduleProfileFetch(event.pubkey)
            }
        }

        // EOSE でローディング解除
        jobs += viewModelScope.launch {
            NostrRepository.eose(histSubId).collect {
                receivedEoseCount++
                if (receivedEoseCount >= expectedEoseCount) {
                    onPageCompleted()
                }
            }
        }

        // タイムアウトフォールバック
        jobs += viewModelScope.launch {
            delay(10_000)
            if (_state.value is UiState.Loading) {
                _state.value = readyState(canLoadMore = false)
            }
        }

        // プロフィール受信
        jobs += viewModelScope.launch {
            NostrRepository.events(profSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                currentProfiles = currentProfiles + (event.pubkey to profile)
                syncReadyState()
            }
        }

        viewModelScope.launch {
            // チャンネルメタ取得
            NostrRepository.subscribe(metaSubId, NostrFilter(ids = listOf(channelId)))
            // 初回ページ
            requestPage(until = null)
        }
    }

    private suspend fun requestPage(until: Long?) {
        loadingMore = true
        lastBatchCount = 0
        receivedEoseCount = 0
        expectedEoseCount = NostrRepository.relayCount.coerceAtLeast(1)
        val current = _state.value as? UiState.Ready
        if (current != null) {
            _state.value = current.copy(canLoadMore = false)
        }

        // 初回のみライブ購読
        if (until == null) {
            NostrRepository.subscribe(
                msgSubId,
                NostrFilter(kinds = listOf(42), eTags = listOf(channelId)),
            )
        }
        NostrRepository.subscribe(
            histSubId,
            NostrFilter(kinds = listOf(42), eTags = listOf(channelId), until = until, limit = PAGE_SIZE),
        )
    }

    private fun onPageCompleted() {
        loadingMore = false
        val hasMore = lastBatchCount >= PAGE_SIZE
        _state.value = readyState(canLoadMore = hasMore)
        if (!hasMore) NostrRepository.close(histSubId)
    }

    private fun appendMessage(event: NostrEvent): Int {
        if (!seenIds.add(event.id)) return 0
        currentMessages = (currentMessages + event).sortedByDescending { it.createdAt }
        oldestCreatedAt = currentMessages.lastOrNull()?.createdAt
        syncReadyState()
        return 1
    }

    private fun readyState(canLoadMore: Boolean): UiState.Ready =
        UiState.Ready(
            channelMeta = currentChannelMeta,
            messages = currentMessages,
            profiles = currentProfiles,
            canLoadMore = canLoadMore,
        )

    private fun syncReadyState() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(
            channelMeta = currentChannelMeta,
            messages = currentMessages,
            profiles = currentProfiles,
        )
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in currentProfiles || pubkey in pendingPubkeys) return
        pendingPubkeys.add(pubkey)
        profileBatchJob?.cancel()
        profileBatchJob = viewModelScope.launch {
            delay(500)
            if (pendingPubkeys.isEmpty()) return@launch
            NostrRepository.subscribe(
                profSubId,
                NostrFilter(kinds = listOf(0), authors = pendingPubkeys.toList()),
            )
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
    }
}
