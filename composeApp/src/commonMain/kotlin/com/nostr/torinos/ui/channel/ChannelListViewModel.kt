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
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
    val hasBeenOpened: Boolean = false,
)

class ChannelListViewModel(private val relayUrl: String? = null) : SafeViewModel() {

    companion object {
        private const val PAGE_TIMEOUT_MS = 10_000L
        private const val NEW_META_DELAY_MS = 300L
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

    data class DeleteDialogState(
        val channelId: String,
        val channelName: String,
        val isDeleting: Boolean = false,
    )

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val channels: List<ChannelItem> = emptyList(),
            val createDialog: CreateDialogState? = null,
            val deleteDialog: DeleteDialogState? = null,
            val canLoadMore: Boolean = false,
            val isLoadingMore: Boolean = false,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val relayKey = relayUrl?.hashCode()?.toString() ?: "all"
    // Phase 1: kind:40 ページング
    private val kind40SubId = "ch-list-kind40-$relayKey"
    // Phase 2: 発見チャンネルの kind:42 最終アクティビティ取得
    private val activitySubId = "ch-list-activity-$relayKey"
    // ライブ: 全 kind:42 受信（新着・新チャンネル検知）
    private val liveSubId = "ch-list-live-$relayKey"
    // ライブで発見した未知チャンネルの kind:40 取得
    private val newMetaSubId = "ch-list-newmeta-$relayKey"
    private val authorsSubId = "ch-list-authors-$relayKey"

    private val channelMap = linkedMapOf<String, ChannelItem>()
    private val lastActivities = mutableMapOf<String, Long>()
    private val seenMessageIds = linkedSetOf<String>()
    private val authorProfiles = mutableMapOf<String, NostrProfile>()
    private val cachedChannels = linkedMapOf<String, CachedChannelSummary>()

    private val jobs = mutableListOf<Job>()
    private val activityJobs = mutableListOf<Job>()
    private val activitySubIds = mutableSetOf<String>()
    private var emitJob: Job? = null
    private var newMetaJob: Job? = null
    private var authorSubscriptionJob: Job? = null
    private var pageTimeoutJob: Job? = null
    private val pendingNewMetaIds = linkedSetOf<String>()
    private val requestedNewMetaIds = mutableSetOf<String>()
    private var subscribedAuthorPubkeys: Set<String> = emptySet()

    private var oldestKind40CreatedAt: Long? = null
    private var loadingMore = false
    private var lastPageKind40Count = 0
    private var currentPageChannelIds = mutableListOf<String>()
    private var receivedEoseCount = 0
    private var expectedEoseCount = 1

    init {
        start()
    }

    private fun start() {
        // DB キャッシュ（Phase 0: 即時表示）
        jobs += launch {
            val cacheRelayUrl = relayUrl ?: return@launch
            ChannelCacheStore.observeChannels(cacheRelayUrl).collect { channels ->
                cachedChannels.clear()
                channels.forEach { cachedChannels[it.channelId] = it }
                emitReady(immediate = _state.value is UiState.Loading)
            }
        }

        // ライブ kind:42: 最終アクティビティ更新 & 未知チャンネル検知
        jobs += launch {
            NostrRepository.events(liveSubId).collect { event ->
                if (event.kind != 42) return@collect
                if (!seenMessageIds.add(event.id)) return@collect
                if (seenMessageIds.size > MAX_SEEN_MSG_IDS) seenMessageIds.remove(seenMessageIds.first())
                val channelId = event.channelIdFromMessage() ?: return@collect
                updateActivity(event, channelId)
                if (!channelMap.containsKey(channelId) && requestedNewMetaIds.add(channelId)) {
                    pendingNewMetaIds.add(channelId)
                    scheduleNewMetaSubscription()
                }
                emitReady(immediate = _state.value is UiState.Loading)
            }
        }

        // Phase 1: kind:40 チャンネルメタ受信
        jobs += launch {
            NostrRepository.events(kind40SubId).collect { event ->
                if (event.kind != 40) return@collect
                val meta = event.toChannelMeta() ?: return@collect
                lastPageKind40Count++
                oldestKind40CreatedAt = minOf(oldestKind40CreatedAt ?: event.createdAt, event.createdAt)
                currentPageChannelIds.add(event.id)
                channelMap[event.id] = ChannelItem(event, meta)
                relayUrl?.let { ChannelCacheStore.upsertChannel(it, event, meta) }
                scheduleAuthorSubscription()
                emitReady()
            }
        }

        // ライブで発見した未知チャンネルの kind:40
        jobs += launch {
            NostrRepository.events(newMetaSubId).collect { event ->
                if (event.kind != 40) return@collect
                val meta = event.toChannelMeta() ?: return@collect
                channelMap[event.id] = ChannelItem(event, meta)
                relayUrl?.let { ChannelCacheStore.upsertChannel(it, event, meta) }
                scheduleAuthorSubscription()
                emitReady()
            }
        }

        // kind:0 プロフィール
        jobs += launch {
            NostrRepository.events(authorsSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                authorProfiles[event.pubkey] = profile
                emitReady()
            }
        }

        // Phase 1 EOSE → Phase 2 発火 + ページ完了
        jobs += launch {
            NostrRepository.eose(kind40SubId).collect {
                receivedEoseCount++
                if (receivedEoseCount >= expectedEoseCount) {
                    triggerActivityFetch()
                    onPageCompleted()
                }
            }
        }

        launch {
            // ライブ購読を常時開始（起動時点以降の新着のみ）
            NostrRepository.subscribe(
                liveSubId,
                NostrFilter(kinds = listOf(42), since = Clock.System.now().epochSeconds),
                relayUrl = relayUrl,
            )
            requestPage(until = null)
        }
    }

    fun loadMore() {
        if (loadingMore || (_state.value as? UiState.Ready)?.canLoadMore != true) return
        launch {
            requestPage(until = oldestKind40CreatedAt?.minus(1))
        }
    }

    fun showDeleteDialog(channelId: String, channelName: String) {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(deleteDialog = DeleteDialogState(channelId, channelName))
    }

    fun dismissDeleteDialog() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(deleteDialog = null)
    }

    fun confirmDelete() {
        val current = _state.value as? UiState.Ready ?: return
        val dialog = current.deleteDialog ?: return
        if (dialog.isDeleting) return
        val cacheRelayUrl = relayUrl ?: return
        _state.value = current.copy(deleteDialog = dialog.copy(isDeleting = true))
        launch {
            runCatching {
                ChannelCacheStore.deleteChannel(cacheRelayUrl, dialog.channelId)
            }
            val s = _state.value as? UiState.Ready ?: return@launch
            _state.value = s.copy(deleteDialog = null)
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
                val event = signEvent(privateKeyHex, content, kind = 40, tags = listOf(listOf("client", "ToriNos")))
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

    private fun updateActivity(event: NostrEvent, channelId: String) {
        val prev = lastActivities[channelId]
        if (prev == null || event.createdAt > prev) {
            lastActivities[channelId] = event.createdAt
        }
        relayUrl?.let {
            launch { ChannelCacheStore.upsertMessage(it, event, channelId) }
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
            deleteDialog = current?.deleteDialog,
            canLoadMore = current?.canLoadMore ?: false,
            isLoadingMore = current?.isLoadingMore ?: false,
        )
    }

    private suspend fun requestPage(until: Long?) {
        loadingMore = true
        lastPageKind40Count = 0
        currentPageChannelIds = mutableListOf()
        receivedEoseCount = 0
        expectedEoseCount = if (relayUrl != null) 1 else NostrRepository.relayCount.coerceAtLeast(1)

        val current = _state.value as? UiState.Ready
        if (current != null) {
            _state.value = current.copy(canLoadMore = false, isLoadingMore = true)
        }
        schedulePageTimeout()

        NostrRepository.subscribe(
            kind40SubId,
            NostrFilter(kinds = listOf(40), until = until, limit = PAGE_SIZE),
            relayUrl = relayUrl,
        )
    }

    private fun triggerActivityFetch() {
        currentPageChannelIds.forEach { channelId ->
            val since = cachedChannels[channelId]?.latestMessageCreatedAt
            val filter = if (since != null) {
                // キャッシュあり: 前回最新以降の差分を取得（最大200件）
                NostrFilter(kinds = listOf(42), eTags = listOf(channelId), since = since, limit = 200)
            } else {
                // キャッシュなし: 最新100件を取得
                NostrFilter(kinds = listOf(42), eTags = listOf(channelId), limit = 100)
            }
            val subId = "$activitySubId-${channelId.take(16)}"
            activitySubIds.add(subId)
            val job = launch {
                NostrRepository.subscribe(subId, filter, relayUrl = relayUrl)
                val collectJob = launch {
                    NostrRepository.events(subId)
                        .filter { it.kind == 42 }
                        .collect { event ->
                            val chId = event.channelIdFromMessage() ?: return@collect
                            if (seenMessageIds.add(event.id)) {
                                if (seenMessageIds.size > MAX_SEEN_MSG_IDS) seenMessageIds.remove(seenMessageIds.first())
                                updateActivity(event, chId)
                                emitReady()
                            }
                        }
                }
                NostrRepository.eose(subId).first()
                collectJob.cancel()
                NostrRepository.close(subId)
                activitySubIds.remove(subId)
            }
            activityJobs.add(job)
        }
    }

    private fun onPageCompleted() {
        if (!loadingMore) return
        loadingMore = false
        pageTimeoutJob?.cancel()
        pageTimeoutJob = null
        val hasMore = lastPageKind40Count >= PAGE_SIZE
        val current = _state.value as? UiState.Ready
        _state.value = UiState.Ready(
            channels = buildChannelList(),
            createDialog = current?.createDialog,
            deleteDialog = current?.deleteDialog,
            canLoadMore = hasMore,
            isLoadingMore = false,
        )
        if (!hasMore) NostrRepository.close(kind40SubId)
    }

    private fun schedulePageTimeout() {
        pageTimeoutJob?.cancel()
        pageTimeoutJob = launch {
            delay(PAGE_TIMEOUT_MS)
            if (!loadingMore) return@launch
            onPageCompleted()
        }
    }

    private fun scheduleNewMetaSubscription() {
        newMetaJob?.cancel()
        newMetaJob = launch {
            delay(NEW_META_DELAY_MS)
            val ids = pendingNewMetaIds.toList()
            pendingNewMetaIds.clear()
            if (ids.isEmpty()) return@launch
            NostrRepository.subscribe(
                newMetaSubId,
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
            .distinct()
            .mapNotNull { channelId ->
                val item = channelMap[channelId] ?: cachedChannels[channelId]?.toChannelItem()
                item?.let {
                    val cached = cachedChannels[channelId]
                    val lastActivityAt = listOfNotNull(
                        lastActivities[channelId],
                        cached?.latestMessageCreatedAt,
                    ).maxOrNull()
                    it.copy(
                        authorProfile = authorProfiles[it.event.pubkey],
                        lastActivityAt = lastActivityAt,
                        latestMessagePreview = cached?.latestMessagePreview,
                        unreadCount = cached?.unreadCount ?: 0,
                        hasBeenOpened = cached?.hasBeenOpened ?: false,
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
        activityJobs.forEach { it.cancel() }
        activitySubIds.forEach { NostrRepository.close(it) }
        newMetaJob?.cancel()
        authorSubscriptionJob?.cancel()
        pageTimeoutJob?.cancel()
        NostrRepository.close(kind40SubId)
        NostrRepository.close(liveSubId)
        NostrRepository.close(newMetaSubId)
        NostrRepository.close(authorsSubId)
    }
}
