package com.example.nostr.ui.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
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

data class ChannelItem(
    val event: NostrEvent,
    val meta: ChannelMeta,
    val authorProfile: NostrProfile? = null,
    val messageCount: Int = 0,
    val lastActivityAt: Long? = null,
)

class ChannelListViewModel : ViewModel() {

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                ChannelListViewModel() as T
        }
    }


    sealed interface UiState {
        data object Loading : UiState
        data class Ready(val channels: List<ChannelItem> = emptyList()) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val listSubId = "ch-list"
    private val msgsSubId = "ch-list-msgs"
    private val authorsSubId = "ch-list-authors"

    // kind:40 ごとの基本情報
    private val channelMap = linkedMapOf<String, ChannelItem>()
    // kind:42 から集計した統計
    private val messageCounts = mutableMapOf<String, Int>()
    private val lastActivities = mutableMapOf<String, Long>()
    private val seenMessageIds = mutableSetOf<String>()
    // kind:0 プロフィール
    private val authorProfiles = mutableMapOf<String, NostrProfile>()

    private val jobs = mutableListOf<Job>()
    private var detailSubscriptionsStarted = false

    init {
        start()
    }

    private fun start() {
        // kind:40 チャンネル情報を収集
        jobs += viewModelScope.launch {
            NostrRepository.events(listSubId).collect { event ->
                if (event.kind != 40) return@collect
                if (channelMap.containsKey(event.id)) return@collect
                val meta = event.toChannelMeta() ?: return@collect
                channelMap[event.id] = ChannelItem(event, meta)
                emitReady()
            }
        }

        // kind:42 メッセージ統計を収集
        jobs += viewModelScope.launch {
            NostrRepository.events(msgsSubId).collect { event ->
                if (event.kind != 42) return@collect
                if (!seenMessageIds.add(event.id)) return@collect
                // root マーカー付き e-tag を優先、なければ最初の e-tag
                val channelId = event.tags
                    .firstOrNull { it.firstOrNull() == "e" && it.getOrNull(3) == "root" }
                    ?.getOrNull(1)
                    ?: event.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                    ?: return@collect
                messageCounts[channelId] = (messageCounts[channelId] ?: 0) + 1
                val prev = lastActivities[channelId]
                if (prev == null || event.createdAt > prev) {
                    lastActivities[channelId] = event.createdAt
                }
                emitReady()
            }
        }

        // kind:0 投稿者プロフィールを収集
        jobs += viewModelScope.launch {
            NostrRepository.events(authorsSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                authorProfiles[event.pubkey] = profile
                emitReady()
            }
        }

        // kind:40 EOSE 後に統計・プロフィール購読を開始
        jobs += viewModelScope.launch {
            NostrRepository.eose(listSubId).collect {
                if (_state.value is UiState.Loading) {
                    _state.value = UiState.Ready(buildChannelList())
                }
                if (detailSubscriptionsStarted) return@collect
                val channelIds = channelMap.keys.toList()
                val authorPubkeys = channelMap.values.map { it.event.pubkey }.distinct()
                detailSubscriptionsStarted = true
                if (channelIds.isNotEmpty()) {
                    NostrRepository.subscribe(
                        msgsSubId,
                        NostrFilter(kinds = listOf(42), eTags = channelIds, limit = 500),
                    )
                }
                if (authorPubkeys.isNotEmpty()) {
                    NostrRepository.subscribe(
                        authorsSubId,
                        NostrFilter(kinds = listOf(0), authors = authorPubkeys),
                    )
                }
            }
        }

        // タイムアウトフォールバック
        jobs += viewModelScope.launch {
            delay(10_000)
            if (_state.value is UiState.Loading) {
                _state.value = UiState.Ready()
            }
        }

        viewModelScope.launch {
            NostrRepository.subscribe(listSubId, NostrFilter(kinds = listOf(40), limit = 100))
        }
    }

    private fun emitReady() {
        _state.value = UiState.Ready(buildChannelList())
    }

    private fun buildChannelList(): List<ChannelItem> =
        channelMap.values
            .map { item ->
                item.copy(
                    authorProfile = authorProfiles[item.event.pubkey],
                    messageCount = messageCounts[item.event.id] ?: 0,
                    lastActivityAt = lastActivities[item.event.id],
                )
            }
            .sortedByDescending { it.lastActivityAt ?: it.event.createdAt }

    override fun onCleared() {
        super.onCleared()
        jobs.forEach { it.cancel() }
        NostrRepository.close(listSubId)
        NostrRepository.close(msgsSubId)
        NostrRepository.close(authorsSubId)
    }
}
