package com.nostr.torinos.ui.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toChannelMeta
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.CachedChannelSummary
import com.nostr.torinos.network.ChannelCacheStore
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.ui.profile.customEmojiMap
import kotlin.reflect.KClass
import kotlin.time.Clock
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
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
    val latestMessageAuthorPubkey: String? = null,
    val latestMessageAuthorProfile: NostrProfile? = null,
    val latestMessagePreview: String? = null,
    val latestMessageCustomEmojis: Map<String, String> = emptyMap(),
    val unreadCount: Int = 0,
    val hasBeenOpened: Boolean = false,
    val isFavorite: Boolean = false,
)

class ChannelListViewModel(
    private val relayUrl: String? = null,
    private val accountSession: AccountSession? = null,
) : SafeViewModel() {

    companion object {
        private const val PAGE_TIMEOUT_MS = 10_000L
        private const val NEW_META_DELAY_MS = 300L
        private const val AUTHOR_SUBSCRIPTION_DELAY_MS = 500L
        private const val EMIT_THROTTLE_MS = 250L
        private const val PAGE_SIZE = 50
        private const val MAX_SEEN_MSG_IDS = 5_000
        private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                ChannelListViewModel() as T
        }
    }

    data class CreateDialogState(
        val name: String = "",
        val about: String = "",
        val body: String = "",
        val isCreating: Boolean = false,
        val error: String? = null,
    )

    data class DeleteDialogState(
        val channelId: String,
        val channelName: String,
        val deleteFromRelays: Boolean = false,
        val isDeleting: Boolean = false,
        val error: String? = null,
    )

    data class BulkDeleteDialogState(
        val isDeleting: Boolean = false,
    )

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val channels: List<ChannelItem> = emptyList(),
            val createDialog: CreateDialogState? = null,
            val deleteDialog: DeleteDialogState? = null,
            val bulkDeleteDialog: BulkDeleteDialogState? = null,
            val createdChannelIdToOpen: String? = null,
            val canLoadMore: Boolean = false,
            val isLoadingMore: Boolean = false,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val relayKey = relayUrl?.hashCode()?.toString() ?: "all"
    // 起動時の kind:40 メタ取得（空チャンネル・新規チャンネルの表示用）
    private val kind40SubId = "ch-list-kind40-$relayKey"
    private val activitySubId = "ch-list-activity-$relayKey"
    // ライブ: 全 kind:42 受信（新着・新チャンネル検知）
    private val liveSubId = "ch-list-live-$relayKey"
    // ライブで発見した未知チャンネルの kind:40 取得
    private val newMetaSubId = "ch-list-newmeta-$relayKey"

    private val channelMap = linkedMapOf<String, ChannelItem>()
    private val lastActivities = mutableMapOf<String, Long>()
    private val seenMessageIds = linkedSetOf<String>()
    private val authorProfiles = mutableMapOf<String, NostrProfile>()
    private val cachedChannels = linkedMapOf<String, CachedChannelSummary>()
    private val cacheReady = CompletableDeferred<Unit>()

    private val jobs = mutableListOf<Job>()
    private val activityQueue = ChannelActivityQueue()
    private var visibleChannelIds: Set<String> = emptySet()
    private var emitJob: Job? = null
    private var newMetaJob: Job? = null
    private var authorSubscriptionJob: Job? = null
    private val pendingNewMetaIds = linkedSetOf<String>()
    private val requestedNewMetaIds = mutableSetOf<String>()
    private var subscribedAuthorPubkeys: Set<String> = emptySet()

    private var loadingMore = false
    private var oldestBootstrapCreatedAt: Long? = null
    private var hasMoreChannels = true
    private var requestSequence = 0L

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
                cacheReady.complete(Unit)
                scheduleAuthorSubscription()
                queueActivityFetches()
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

        // 共通プロフィールキャッシュ
        jobs += launch {
            ProfileRepository.observeAll().collect { cachedProfiles ->
                val profiles = cachedProfiles.filterKeys { it in subscribedAuthorPubkeys }
                if (profiles != authorProfiles) {
                    authorProfiles.clear()
                    authorProfiles.putAll(profiles)
                    emitReady()
                }
            }
        }

        launch {
            // キャッシュを先に公開する。DB が応答しない場合もネットワーク取得へ進む。
            if (relayUrl != null) withTimeoutOrNull(PAGE_TIMEOUT_MS) { cacheReady.await() }
            // ライブ購読を常時開始（起動時点以降の新着のみ）
            NostrRepository.subscribe(
                liveSubId,
                NostrFilter(kinds = listOf(42), since = Clock.System.now().epochSeconds),
                relayUrl = relayUrl,
            )
            loadMore()
        }
    }

    fun loadMore() {
        if (loadingMore || !hasMoreChannels) return
        loadingMore = true
        emitReady(immediate = true)
        launch {
            var count = 0
            var oldest: Long? = null
            try {
                val completed = fetch(kind40SubId, NostrFilter(
                    kinds = listOf(40), until = oldestBootstrapCreatedAt?.minus(1), limit = PAGE_SIZE,
                )) { event ->
                    if (event.kind == 40) {
                        count++
                        oldest = minOf(oldest ?: event.createdAt, event.createdAt)
                        acceptChannel(event)
                    }
                }
                // 失敗・無応答時は同じページを手動で再試行できるようにする。
                if (completed) {
                    oldestBootstrapCreatedAt = oldest ?: oldestBootstrapCreatedAt
                    hasMoreChannels = count >= PAGE_SIZE
                }
            } finally {
                loadingMore = false
                queueActivityFetches()
                emitReady(immediate = true)
            }
        }
    }

    fun setVisibleChannels(channelIds: Set<String>) {
        visibleChannelIds = channelIds
        scheduleAuthorSubscription()
        queueActivityFetches()
    }

    private suspend fun acceptChannel(event: NostrEvent) {
        val meta = event.toChannelMeta() ?: return
        if (event.id !in channelMap) channelMap[event.id] = ChannelItem(event, meta)
        relayUrl?.let { ChannelCacheStore.upsertChannel(it, event, meta) }
        scheduleAuthorSubscription()
        emitReady()
    }

    fun showDeleteDialog(channelId: String, channelName: String, deleteFromRelays: Boolean) {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(
            deleteDialog = DeleteDialogState(
                channelId = channelId,
                channelName = channelName,
                deleteFromRelays = deleteFromRelays,
            ),
        )
    }

    fun dismissDeleteDialog() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(deleteDialog = null)
    }

    fun confirmDelete() {
        val current = _state.value as? UiState.Ready ?: return
        val dialog = current.deleteDialog ?: return
        if (dialog.isDeleting) return
        _state.value = current.copy(deleteDialog = dialog.copy(isDeleting = true, error = null))
        launch {
            if (dialog.deleteFromRelays) {
                val signer = accountSession?.signer ?: run {
                    val s = _state.value as? UiState.Ready ?: return@launch
                    _state.value = s.copy(
                        deleteDialog = s.deleteDialog?.copy(
                            isDeleting = false,
                            error = "秘密鍵が設定されていません",
                        ),
                    )
                    return@launch
                }
                runCatching {
                    val deletion = signer.sign(
                        content = "",
                        kind = 5,
                        tags = listOf(
                            listOf("e", dialog.channelId),
                            listOf("k", "40"),
                            listOf("client", "ToriNos"),
                        ),
                    )
                    NostrRepository.publish(deletion)
                    relayUrl?.let { ChannelCacheStore.deleteChannel(it, dialog.channelId) }
                }.onSuccess {
                    channelMap.remove(dialog.channelId)
                    cachedChannels.remove(dialog.channelId)
                    val s = _state.value as? UiState.Ready ?: return@launch
                    _state.value = s.copy(
                        channels = buildChannelList(),
                        deleteDialog = null,
                    )
                }.onFailure { e ->
                    val s = _state.value as? UiState.Ready ?: return@launch
                    _state.value = s.copy(
                        deleteDialog = s.deleteDialog?.copy(
                            isDeleting = false,
                            error = e.message ?: "削除要求を送信できませんでした",
                        ),
                    )
                }
            } else {
                relayUrl?.let { cacheRelayUrl ->
                    runCatching {
                        ChannelCacheStore.deleteChannel(cacheRelayUrl, dialog.channelId)
                    }
                }
                val s = _state.value as? UiState.Ready ?: return@launch
                _state.value = s.copy(deleteDialog = null)
            }
        }
    }

    fun toggleFavorite(channelId: String) {
        val cacheRelayUrl = relayUrl ?: return
        val current = _state.value as? UiState.Ready ?: return
        val item = current.channels.firstOrNull { it.event.id == channelId } ?: return
        val newFavorite = !item.isFavorite
        launch {
            ChannelCacheStore.setFavorite(cacheRelayUrl, channelId, newFavorite)
        }
    }

    fun showBulkDeleteDialog() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(bulkDeleteDialog = BulkDeleteDialogState())
    }

    fun dismissBulkDeleteDialog() {
        val current = _state.value as? UiState.Ready ?: return
        if (current.bulkDeleteDialog?.isDeleting == true) return
        _state.value = current.copy(bulkDeleteDialog = null)
    }

    fun confirmBulkDelete() {
        val current = _state.value as? UiState.Ready ?: return
        val dialog = current.bulkDeleteDialog ?: return
        if (dialog.isDeleting) return
        val cacheRelayUrl = relayUrl ?: return
        _state.value = current.copy(bulkDeleteDialog = dialog.copy(isDeleting = true))
        launch {
            runCatching {
                ChannelCacheStore.deleteNonFavorites(cacheRelayUrl)
            }
            val s = _state.value as? UiState.Ready ?: return@launch
            _state.value = s.copy(bulkDeleteDialog = null)
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

    fun consumeCreatedChannelNavigation() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(createdChannelIdToOpen = null)
    }

    fun onCreateNameChange(name: String) {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(createDialog = current.createDialog?.copy(name = name, error = null))
    }

    fun onCreateAboutChange(about: String) {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(createDialog = current.createDialog?.copy(about = about, error = null))
    }

    fun onCreateBodyChange(body: String) {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(createDialog = current.createDialog?.copy(body = body, error = null))
    }

    fun createChannel() {
        val current = _state.value as? UiState.Ready ?: return
        val dialog = current.createDialog ?: return
        if (dialog.name.isBlank() || dialog.isCreating) return
        _state.value = current.copy(createDialog = dialog.copy(isCreating = true, error = null))
        launch {
            val signer = accountSession?.signer ?: run {
                val s = _state.value as? UiState.Ready ?: return@launch
                _state.value = s.copy(createDialog = s.createDialog?.copy(isCreating = false, error = "秘密鍵が設定されていません"))
                return@launch
            }
            runCatching {
                val meta = ChannelMeta(
                    name = dialog.name.trim(),
                    about = dialog.about.trim(),
                    picture = "",
                )
                val content = buildJsonObject {
                    put("name", meta.name)
                    put("about", meta.about)
                    put("picture", "")
                }.toString()
                val event = signer.sign(content, kind = 40, tags = listOf(listOf("client", "ToriNos")))
                NostrRepository.publish(event)
                val firstPost = dialog.body.trim().takeIf { it.isNotBlank() }?.let { body ->
                    signer.sign(
                        content = body,
                        kind = 42,
                        tags = listOf(listOf("e", event.id, "", "root"), listOf("client", "ToriNos")),
                    ).also { NostrRepository.publish(it) }
                }
                Triple(event, meta, firstPost)
            }.onSuccess { (event, meta, firstPost) ->
                channelMap[event.id] = ChannelItem(event, meta)
                lastActivities[event.id] = firstPost?.createdAt ?: event.createdAt
                relayUrl?.let { ChannelCacheStore.upsertChannel(it, event, meta) }
                firstPost?.let { post ->
                    seenMessageIds.add(post.id)
                    updateActivity(post, event.id)
                }
                scheduleAuthorSubscription()
                val s = _state.value as? UiState.Ready ?: return@launch
                _state.value = s.copy(
                    channels = buildChannelList(),
                    createDialog = null,
                    createdChannelIdToOpen = event.id,
                )
            }.onFailure { e ->
                val s = _state.value as? UiState.Ready ?: return@launch
                _state.value = s.copy(createDialog = s.createDialog?.copy(isCreating = false, error = e.message ?: "作成に失敗しました"))
            }
        }
    }

    private fun updateActivity(event: NostrEvent, channelId: String) {
        val prev = lastActivities[channelId]
        val isLatest = prev == null || event.createdAt >= prev
        if (prev == null || event.createdAt > prev) {
            lastActivities[channelId] = event.createdAt
        }
        val current = channelMap[channelId]
        if (current != null && isLatest) {
            channelMap[channelId] = current.copy(
                latestMessageAuthorPubkey = event.pubkey,
                latestMessageAuthorProfile = authorProfiles[event.pubkey],
                latestMessagePreview = event.content,
                latestMessageCustomEmojis = event.tags.customEmojiMap(),
            )
            scheduleAuthorSubscription()
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
            bulkDeleteDialog = current?.bulkDeleteDialog,
            createdChannelIdToOpen = current?.createdChannelIdToOpen,
            canLoadMore = hasMoreChannels,
            isLoadingMore = loadingMore,
        )
    }

    private suspend fun fetch(
        prefix: String,
        filter: NostrFilter,
        onEvent: suspend (NostrEvent) -> Unit,
    ): Boolean = fetchChannelEvents(
        SubscriptionSpec(
            id = "$prefix-${requestSequence++}",
            filters = listOf(filter),
            target = relayUrl?.let(RelayTarget::Single) ?: RelayTarget.AllEnabled,
            behavior = SubscriptionBehavior.Fetch(PAGE_TIMEOUT_MS),
        ),
        onEvent = onEvent,
    )

    private fun queueActivityFetches() {
        activityQueue.enqueue(channelMap.keys + cachedChannels.keys)
        activityQueue.prioritize(
            cachedChannels.values.filter { it.isFavorite }.map { it.channelId }.toSet(),
            visibleChannelIds,
        )
        while (true) {
            val channelId = activityQueue.takeNext() ?: break
            launch {
                try {
                    yield()
                    val since = cachedChannels[channelId]?.latestMessageCreatedAt
                    fetch(activitySubId, NostrFilter(
                        kinds = listOf(42), eTags = listOf(channelId), since = since,
                        limit = if (since != null) 200 else 100,
                    )) { event ->
                        if (event.kind == 42 && event.channelIdFromMessage() == channelId && seenMessageIds.add(event.id)) {
                            if (seenMessageIds.size > MAX_SEEN_MSG_IDS) seenMessageIds.remove(seenMessageIds.first())
                            updateActivity(event, channelId)
                            emitReady()
                        }
                    }
                } finally {
                    activityQueue.complete(channelId)
                    queueActivityFetches()
                }
            }
        }
    }

    private fun scheduleNewMetaSubscription() {
        if (newMetaJob?.isActive == true) return
        newMetaJob = launch {
            delay(NEW_META_DELAY_MS)
            while (pendingNewMetaIds.isNotEmpty()) {
                val ids = pendingNewMetaIds.take(PAGE_SIZE)
                pendingNewMetaIds.removeAll(ids.toSet())
                fetch(newMetaSubId, NostrFilter(ids = ids, kinds = listOf(40))) { acceptChannel(it) }
                queueActivityFetches()
            }
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
        val authorPubkeys = (
            channelMap.values.map { it.event.pubkey } +
                channelMap.values.mapNotNull { it.latestMessageAuthorPubkey } +
                cachedChannels.values.map { it.ownerPubkey } +
                cachedChannels.values.mapNotNull { it.latestMessageAuthorPubkey }
            )
            .toSet()
        if (authorPubkeys.isNotEmpty() && authorPubkeys != subscribedAuthorPubkeys) {
            subscribedAuthorPubkeys = authorPubkeys
            authorProfiles.putAll(ProfileRepository.getCached(authorPubkeys))
            ProfileRepository.ensureProfiles(
                authorPubkeys,
                ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS),
                relayHint = relayUrl,
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
                        latestMessageAuthorPubkey = cached?.latestMessageAuthorPubkey ?: it.latestMessageAuthorPubkey,
                        latestMessageAuthorProfile = (cached?.latestMessageAuthorPubkey ?: it.latestMessageAuthorPubkey)
                            ?.let { pubkey -> authorProfiles[pubkey] },
                        latestMessagePreview = cached?.latestMessagePreview ?: it.latestMessagePreview,
                        latestMessageCustomEmojis = it.latestMessageCustomEmojis,
                        unreadCount = cached?.unreadCount ?: 0,
                        hasBeenOpened = cached?.hasBeenOpened ?: false,
                        isFavorite = cached?.isFavorite ?: false,
                    )
                }
            }
            .sortedWith(
                compareBy<ChannelItem> { it.lastActivityAt == null }
                    .thenByDescending { it.lastActivityAt ?: it.event.createdAt },
            )

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
            latestMessageAuthorPubkey = latestMessageAuthorPubkey,
            latestMessagePreview = latestMessagePreview,
            unreadCount = unreadCount,
            isFavorite = isFavorite,
        )

    private fun NostrEvent.channelIdFromMessage(): String? =
        tags
            .firstOrNull { it.firstOrNull() == "e" && it.getOrNull(3) == "root" }
            ?.getOrNull(1)
            ?: tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)

    override fun onCleared() {
        activityQueue.stop()
        super.onCleared()
        jobs.forEach { it.cancel() }
        newMetaJob?.cancel()
        authorSubscriptionJob?.cancel()
        NostrRepository.close(liveSubId)
    }
}
