package com.nostr.torinos.ui.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toChannelMeta
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.ui.SafeViewModel
import kotlin.reflect.KClass
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ChannelItem(
    val event: NostrEvent,
    val meta: ChannelMeta,
    val authorProfile: NostrProfile? = null,
    val messageCount: Int = 0,
    val lastActivityAt: Long? = null,
)

class ChannelListViewModel : SafeViewModel() {

    companion object {
        private const val READY_FALLBACK_DELAY_MS = 2_500L
        private const val DETAIL_SUBSCRIPTION_DELAY_MS = 500L
        private const val EMIT_THROTTLE_MS = 250L
        private const val MAX_ETAGS = 50
        private const val MSGS_LIMIT = 200

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                ChannelListViewModel() as T
        }
    }


    data class CreateDialogState(
        val name: String = "",
        val about: String = "",
        val isCreating: Boolean = false,
        val error: String? = null,
    )

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val channels: List<ChannelItem> = emptyList(),
            val createDialog: CreateDialogState? = null,
        ) : UiState
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
    private var emitJob: Job? = null
    private var detailSubscriptionJob: Job? = null
    private var subscribedChannelIds: Set<String> = emptySet()
    private var subscribedAuthorPubkeys: Set<String> = emptySet()

    init {
        start()
    }

    private fun start() {
        // kind:40 チャンネル情報を収集
        jobs += launch {
            NostrRepository.events(listSubId).collect { event ->
                if (event.kind != 40) return@collect
                if (channelMap.containsKey(event.id)) return@collect
                val meta = event.toChannelMeta() ?: return@collect
                channelMap[event.id] = ChannelItem(event, meta)
                emitReady(immediate = _state.value is UiState.Loading)
                scheduleDetailSubscriptions()
            }
        }

        // kind:42 メッセージ統計を収集
        jobs += launch {
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

        // kind:0 ポスト作成者プロフィールを収集
        jobs += launch {
            NostrRepository.events(authorsSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                authorProfiles[event.pubkey] = profile
                emitReady()
            }
        }

        // EOSE を待てるリレーでは即座に空表示/詳細購読へ進める。
        jobs += launch {
            NostrRepository.eose(listSubId).collect {
                if (_state.value is UiState.Loading) {
                    _state.value = UiState.Ready(buildChannelList())
                }
                refreshDetailSubscriptions()
            }
        }

        // EOSE を返さないリレーでもローディングを長く残さない。
        jobs += launch {
            delay(READY_FALLBACK_DELAY_MS)
            if (_state.value is UiState.Loading) {
                _state.value = UiState.Ready()
            }
        }

        launch {
            NostrRepository.subscribe(listSubId, NostrFilter(kinds = listOf(40), limit = 100))
        }
    }

    fun showCreateDialog() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(createDialog = CreateDialogState())
    }

    fun dismissCreateDialog() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(createDialog = null)
    }

    fun onCreateNameChange(name: String) {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(createDialog = current.createDialog?.copy(name = name, error = null))
    }

    fun onCreateAboutChange(about: String) {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(createDialog = current.createDialog?.copy(about = about, error = null))
    }

    fun createChannel() {
        val current = _state.value as? UiState.Ready ?: return
        val dialog = current.createDialog ?: return
        if (dialog.name.isBlank() || dialog.isCreating) return
        _state.value = current.copy(createDialog = dialog.copy(isCreating = true, error = null))
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: run {
                val s = _state.value as? UiState.Ready ?: return@launch
                _state.value = s.copy(createDialog = s.createDialog?.copy(isCreating = false, error = "秘密鍵が設定されていません"))
                return@launch
            }
            runCatching {
                val content = buildJsonObject {
                    put("name", dialog.name.trim())
                    put("about", dialog.about.trim())
                    put("picture", "")
                }.toString()
                val event = signEvent(privateKeyHex, content, kind = 40)
                NostrRepository.publish(event)
            }.onSuccess {
                val s = _state.value as? UiState.Ready ?: return@launch
                _state.value = s.copy(createDialog = null)
            }.onFailure { e ->
                val s = _state.value as? UiState.Ready ?: return@launch
                _state.value = s.copy(createDialog = s.createDialog?.copy(isCreating = false, error = e.message ?: "作成に失敗しました"))
            }
        }
    }

    private fun emitReady(immediate: Boolean = false) {
        if (immediate) {
            emitJob?.cancel()
            emitReadyNow()
            return
        }
        if (emitJob?.isActive == true) return
        emitJob = launch {
            delay(EMIT_THROTTLE_MS)
            emitReadyNow()
        }
    }

    private fun emitReadyNow() {
        val current = _state.value as? UiState.Ready
        _state.value = UiState.Ready(buildChannelList(), current?.createDialog)
    }

    private fun scheduleDetailSubscriptions() {
        detailSubscriptionJob?.cancel()
        detailSubscriptionJob = launch {
            delay(DETAIL_SUBSCRIPTION_DELAY_MS)
            refreshDetailSubscriptions()
        }
    }

    private suspend fun refreshDetailSubscriptions() {
        val channelIds = channelMap.keys.toSet()
        val authorPubkeys = channelMap.values.map { it.event.pubkey }.toSet()

        if (channelIds.isNotEmpty() && channelIds != subscribedChannelIds) {
            subscribedChannelIds = channelIds
            val recentIds = channelMap.entries
                .sortedByDescending { it.value.event.createdAt }
                .take(MAX_ETAGS)
                .map { it.key }
            NostrRepository.subscribe(
                msgsSubId,
                NostrFilter(kinds = listOf(42), eTags = recentIds, limit = MSGS_LIMIT),
            )
        }
        if (authorPubkeys.isNotEmpty() && authorPubkeys != subscribedAuthorPubkeys) {
            subscribedAuthorPubkeys = authorPubkeys
            NostrRepository.subscribe(
                authorsSubId,
                NostrFilter(kinds = listOf(0), authors = authorPubkeys.toList()),
            )
        }
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
