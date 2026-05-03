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
import com.nostr.torinos.network.CachedChannelSummary
import com.nostr.torinos.network.ChannelCacheStore
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
    val latestMessagePreview: String? = null,
    val unreadCount: Int = 0,
)

class ChannelListViewModel(private val relayUrl: String? = null) : SafeViewModel() {

    companion object {
        private const val PAGE_TIMEOUT_MS = 10_000L
        private const val META_SUBSCRIPTION_DELAY_MS = 300L
        private const val AUTHOR_SUBSCRIPTION_DELAY_MS = 500L
        private const val EMIT_THROTTLE_MS = 250L
        private const val PAGE_SIZE = 50
        private const val MAX_SEEN_MSG_IDS = 5_000

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
            val canLoadMore: Boolean = false,
            val isLoadingMore: Boolean = false,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val relayKey = relayUrl?.hashCode()?.toString() ?: "all"
    private val activitySubId = "ch-list-activity-$relayKey"
    private val metaSubId = "ch-list-meta-$relayKey"
    private val authorsSubId = "ch-list-authors-$relayKey"

    // kind:40 ごとの基本情報
    private val channelMap = linkedMapOf<String, ChannelItem>()
    // kind:42 から集計した統計
    private val messageCounts = mutableMapOf<String, Int>()
    private val lastActivities = mutableMapOf<String, Long>()
    private val seenMessageIds = linkedSetOf<String>()
    // kind:0 プロフィール
    private val authorProfiles = mutableMapOf<String, NostrProfile>()
    private val cachedChannels = linkedMapOf<String, CachedChannelSummary>()

    private val jobs = mutableListOf<Job>()
    private var emitJob: Job? = null
    private var metaSubscriptionJob: Job? = null
    private var authorSubscriptionJob: Job? = null
    private var pageTimeoutJob: Job? = null
    private val pendingMetaChannelIds = linkedSetOf<String>()
    private val requestedMetaChannelIds = mutableSetOf<String>()
    private var subscribedAuthorPubkeys: Set<String> = emptySet()
    private var oldestActivityCreatedAt: Long? = null
    private var loadingMore = false
    private var lastPageUniqueMessageCount = 0
    private var receivedEoseCount = 0
    private var expectedEoseCount = 1

    init {
        start()
    }

    private fun start() {
        // kind:42 の直近メッセージから、更新があったチャンネルを収集
        jobs += launch {
            val cacheRelayUrl = relayUrl ?: return@launch
            ChannelCacheStore.observeChannels(cacheRelayUrl).collect { channels ->
                cachedChannels.clear()
                channels.forEach { cachedChannels[it.channelId] = it }
                emitReady(immediate = _state.value is UiState.Loading)
            }
        }

        jobs += launch {
            NostrRepository.events(activitySubId).collect { event ->
                if (event.kind != 42) return@collect
                if (!seenMessageIds.add(event.id)) return@collect
                if (seenMessageIds.size > MAX_SEEN_MSG_IDS) seenMessageIds.remove(seenMessageIds.first())
                lastPageUniqueMessageCount++
                oldestActivityCreatedAt = minOf(oldestActivityCreatedAt ?: event.createdAt, event.createdAt)
                val channelId = event.channelIdFromMessage() ?: return@collect
                messageCounts[channelId] = (messageCounts[channelId] ?: 0) + 1
                val prev = lastActivities[channelId]
                if (prev == null || event.createdAt > prev) {
                    lastActivities[channelId] = event.createdAt
                }
                relayUrl?.let { ChannelCacheStore.upsertMessage(it, event, channelId) }
                if (!channelMap.containsKey(channelId) && requestedMetaChannelIds.add(channelId)) {
                    pendingMetaChannelIds.add(channelId)
                    scheduleMetaSubscription()
                }
                emitReady(immediate = _state.value is UiState.Loading)
            }
        }

        // kind:40 チャンネルメタを収集
        jobs += launch {
            NostrRepository.events(metaSubId).collect { event ->
                if (event.kind != 40) return@collect
                val meta = event.toChannelMeta() ?: return@collect
                channelMap[event.id] = ChannelItem(event, meta)
                relayUrl?.let { ChannelCacheStore.upsertChannel(it, event, meta) }
                scheduleAuthorSubscription()
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

        // kind:42 ページの EOSE で追加読み込み可否を決める。
        jobs += launch {
            NostrRepository.eose(activitySubId).collect {
                receivedEoseCount++
                if (receivedEoseCount >= expectedEoseCount) onPageCompleted()
            }
        }

        launch {
            requestPage(until = null)
        }
    }

    fun loadMore() {
        if (loadingMore || (_state.value as? UiState.Ready)?.canLoadMore != true) return
        launch {
            requestPage(until = oldestActivityCreatedAt?.minus(1))
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
        _state.value = UiState.Ready(
            channels = buildChannelList(),
            createDialog = current?.createDialog,
            canLoadMore = current?.canLoadMore ?: false,
            isLoadingMore = current?.isLoadingMore ?: false,
        )
    }

    private suspend fun requestPage(until: Long?) {
        loadingMore = true
        lastPageUniqueMessageCount = 0
        receivedEoseCount = 0
        expectedEoseCount = if (relayUrl != null) 1 else NostrRepository.relayCount.coerceAtLeast(1)

        val current = _state.value as? UiState.Ready
        if (current != null) {
            _state.value = current.copy(canLoadMore = false, isLoadingMore = true)
        }
        schedulePageTimeout()

        NostrRepository.subscribe(
            activitySubId,
            NostrFilter(kinds = listOf(42), until = until, limit = PAGE_SIZE),
            relayUrl = relayUrl,
        )
    }

    private fun onPageCompleted() {
        if (!loadingMore) return
        loadingMore = false
        pageTimeoutJob?.cancel()
        pageTimeoutJob = null
        val hasMore = lastPageUniqueMessageCount >= PAGE_SIZE
        val current = _state.value as? UiState.Ready
        _state.value = UiState.Ready(
            channels = buildChannelList(),
            createDialog = current?.createDialog,
            canLoadMore = hasMore,
            isLoadingMore = false,
        )
        if (!hasMore) NostrRepository.close(activitySubId)
    }

    private fun schedulePageTimeout() {
        pageTimeoutJob?.cancel()
        pageTimeoutJob = launch {
            delay(PAGE_TIMEOUT_MS)
            if (!loadingMore) return@launch
            onPageCompleted()
        }
    }

    private fun scheduleMetaSubscription() {
        metaSubscriptionJob?.cancel()
        metaSubscriptionJob = launch {
            delay(META_SUBSCRIPTION_DELAY_MS)
            val ids = pendingMetaChannelIds.toList()
            pendingMetaChannelIds.clear()
            if (ids.isEmpty()) return@launch
            NostrRepository.subscribe(
                metaSubId,
                NostrFilter(ids = ids),
                relayUrl = relayUrl,
            )
        }
    }

    private fun scheduleAuthorSubscription() {
        authorSubscriptionJob?.cancel()
        authorSubscriptionJob = launch {
            delay(AUTHOR_SUBSCRIPTION_DELAY_MS)
            refreshAuthorSubscription()
        }
    }

    private suspend fun refreshAuthorSubscription() {
        val authorPubkeys = channelMap.values.map { it.event.pubkey }.toSet()

        if (authorPubkeys.isNotEmpty() && authorPubkeys != subscribedAuthorPubkeys) {
            subscribedAuthorPubkeys = authorPubkeys
            NostrRepository.subscribe(
                authorsSubId,
                NostrFilter(kinds = listOf(0), authors = authorPubkeys.toList()),
                relayUrl = relayUrl,
            )
        }
    }

    private fun buildChannelList(): List<ChannelItem> =
        (cachedChannels.keys + channelMap.keys)
            .mapNotNull { channelId ->
                val item = channelMap[channelId] ?: cachedChannels[channelId]?.toChannelItem()
                item?.let {
                    val cached = cachedChannels[channelId]
                    val networkLastActivity = lastActivities[channelId]
                    val cachedLastActivity = cached?.latestMessageCreatedAt
                    val lastActivityAt = listOfNotNull(networkLastActivity, cachedLastActivity).maxOrNull()
                    it.copy(
                        authorProfile = authorProfiles[it.event.pubkey],
                        messageCount = messageCounts[channelId] ?: 0,
                        lastActivityAt = lastActivityAt,
                        latestMessagePreview = cached?.latestMessagePreview,
                        unreadCount = cached?.unreadCount ?: 0,
                    )
                }
            }
            .sortedByDescending { it.lastActivityAt ?: it.event.createdAt }

    private fun CachedChannelSummary.toChannelItem(): ChannelItem =
        ChannelItem(
            event = NostrEvent(
                id = channelId,
                pubkey = ownerPubkey,
                createdAt = createdAt,
                kind = 40,
                tags = emptyList(),
                content = "",
                sig = "",
            ),
            meta = ChannelMeta(
                name = name,
                about = about,
                picture = picture,
            ),
            messageCount = 0,
            lastActivityAt = latestMessageCreatedAt,
            latestMessagePreview = latestMessagePreview,
            unreadCount = unreadCount,
        )

    private fun NostrEvent.channelIdFromMessage(): String? =
        tags
            .firstOrNull { it.firstOrNull() == "e" && it.getOrNull(3) == "root" }
            ?.getOrNull(1)
            ?: tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)

    override fun onCleared() {
        super.onCleared()
        jobs.forEach { it.cancel() }
        metaSubscriptionJob?.cancel()
        authorSubscriptionJob?.cancel()
        pageTimeoutJob?.cancel()
        NostrRepository.close(activitySubId)
        NostrRepository.close(metaSubId)
        NostrRepository.close(authorsSubId)
    }
}
