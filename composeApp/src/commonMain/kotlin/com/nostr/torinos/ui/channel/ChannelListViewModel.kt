package com.nostr.torinos.ui.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.ChannelMetadataResolver
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toChannelMeta
import com.nostr.torinos.network.ChannelLocalState
import com.nostr.torinos.network.ChannelLocalStore
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayInformationRepository
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.network.normalizeRelayUrls
import com.nostr.torinos.network.normalizeRelayUrl
import com.nostr.torinos.network.ImageUploader
import com.nostr.torinos.ui.post.ComposedNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nostr.torinos.model.ChannelEventTags
import com.nostr.torinos.model.toChannelContent
import com.nostr.torinos.ui.timeline.SignedEventPublisher
import com.nostr.torinos.ui.timeline.SignedPublishResult
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.emoji.customEmojiMap
import kotlin.reflect.KClass
import kotlin.time.Clock
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

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
    /** 起動時キャッチアップが上限に達し、実際の未読は [unreadCount] 以上ありうる。 */
    val unreadCountIsLowerBound: Boolean = false,
    /** 未開封チャンネルでセッション中に新着を観測した(第16.12.4節)。 */
    val hasNewActivity: Boolean = false,
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
        private const val RELAY_INFO_TIMEOUT_MS = 3_000L
        private const val MAX_RECOMMENDED_RELAYS = 10

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                ChannelListViewModel() as T
        }
    }

    data class CreateDialogState(
        val name: String = "",
        val about: String = "",
        /** アップロード済みのアイコン画像 URL(FR-13)。 */
        val picture: String = "",
        val isUploadingPicture: Boolean = false,
        /** 推奨リレーの候補。ユーザーの書き込みリレー + 手入力で追加したリレー(正規化済み)。 */
        val candidateRelays: List<String> = emptyList(),
        val selectedRelays: Set<String> = emptySet(),
        val isCreating: Boolean = false,
        val error: String? = null,
        /**
         * kind 40 は送信済みで、最初の kind 42 だけが失敗した状態。再度「作成」を押すと投稿だけを再送し、
         * チャンネルを二重に作らない(第16.11節 手順5)。
         */
        val createdChannel: NostrEvent? = null,
        /** 直近の送信(kind 40 または最初の kind 42)のリレー別結果。 */
        val publishState: ChannelPublishUiState = ChannelPublishUiState.Idle,
        val sessionId: Long = 0,
        val activePublishKind: Int = 40,
    ) {
        val isRetryingFirstPost: Boolean get() = createdChannel != null

        /** `content.relays` に入れる推奨リレー。候補の並び順を保つ。 */
        val recommendedRelays: List<String> get() = candidateRelays.filter { it in selectedRelays }

        val canSubmit: Boolean get() = name.isNotBlank() && !isCreating && !isUploadingPicture

        val hasInput: Boolean get() = name.isNotBlank() || about.isNotBlank() || picture.isNotBlank()
    }

    data class DeleteDialogState(
        val channelId: String,
        val channelName: String,
        val deleteFromRelays: Boolean = false,
        val isDeleting: Boolean = false,
        val error: String? = null,
    )

    data class DetailDialogState(
        val channelId: String,
        val channelName: String,
        val events: List<NostrEvent> = emptyList(),
        val isLoading: Boolean = true,
        val error: String? = null,
    )

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val channels: List<ChannelItem> = emptyList(),
            val createDialog: CreateDialogState? = null,
            val deleteDialog: DeleteDialogState? = null,
            val detailDialog: DetailDialogState? = null,
            val createdChannelIdToOpen: String? = null,
            /** 作成は成功したが一部のリレーへ送れなかったときなどの案内(snackbar)。 */
            val notice: String? = null,
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
    private val catchUpSubId = "ch-list-unread-$relayKey"

    private val channelMap = linkedMapOf<String, ChannelItem>()
    private val lastActivities = mutableMapOf<String, Long>()
    private val seenMessageIds = linkedSetOf<String>()
    private val authorProfiles = mutableMapOf<String, NostrProfile>()
    private val cachedChannels = linkedMapOf<String, ChannelLocalState>()
    private val cacheReady = CompletableDeferred<Unit>()
    private val unreadTracker = ChannelUnreadTracker()
    // 参加中チャンネル(kind 10005)。ログイン中のアカウントだけ同期する(匿名は端末内の★のみ)。
    private val joinedChannels = accountSession?.let { session -> JoinedChannelRepository(session.pubkey, session.signer) }
    private var joinedIds: Set<String> = emptySet()
    private var joinedRefreshStarted = false
    private var catchUpStarted = false
    // ライブ購読の since。未読キャッチアップは until = liveSince - 1 として時刻で重ならないようにする。
    private val liveSince = Clock.System.now().epochSeconds

    private val jobs = mutableListOf<Job>()
    private val activityQueue = ChannelActivityQueue()
    private var visibleChannelIds: Set<String> = emptySet()
    private var emitJob: Job? = null
    private var newMetaJob: Job? = null
    private var authorSubscriptionJob: Job? = null
    private var detailDialogJob: Job? = null
    private var createPictureUploadJob: Job? = null
    private val pendingNewMetaIds = linkedSetOf<String>()
    private val requestedNewMetaIds = mutableSetOf<String>()
    private var subscribedAuthorPubkeys: Set<String> = emptySet()

    private var loadingMore = false
    private var oldestBootstrapCreatedAt: Long? = null
    private var hasMoreChannels = true
    private var requestSequence = 0L
    private var createSessionSequence = 0L
    private val createPublishStates = mutableMapOf<String, ChannelPublishUiState>()
    private val reportedPartialCreateEvents = mutableSetOf<String>()

    init {
        start()
    }

    private fun start() {
        // 端末ローカル状態（Phase 0: 即時表示）
        jobs += launch {
            val cacheRelayUrl = relayUrl ?: return@launch
            ChannelLocalStore.observe(cacheRelayUrl).collect { channels ->
                cachedChannels.clear()
                channels
                    .sortedWith(
                        compareByDescending<ChannelLocalState> { it.isFavorite }
                            .thenByDescending { it.latestMessage?.createdAt ?: it.channelCreatedAt },
                    )
                    .forEach { cachedChannels[it.channelId] = it }
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
                unreadTracker.onLive(channelId, event.id, event.createdAt)
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
            ProfileRepository.observeChanges().collect { changedPubkeys ->
                val affected = if (changedPubkeys.isEmpty()) {
                    subscribedAuthorPubkeys
                } else {
                    changedPubkeys.intersect(subscribedAuthorPubkeys)
                }
                if (affected.isEmpty()) return@collect
                val profiles = ProfileRepository.getCached(affected)
                var changed = false
                affected.forEach { pubkey ->
                    val profile = profiles[pubkey]
                    if (profile == null) {
                        if (authorProfiles.remove(pubkey) != null) changed = true
                    } else if (authorProfiles[pubkey] != profile) {
                        authorProfiles[pubkey] = profile
                        changed = true
                    }
                }
                if (changed) {
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
                NostrFilter(kinds = listOf(42), since = liveSince),
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
                // 初回ページで観測元リレーを記録してから始める。初訪問のリレーでは端末状態が空のため、
                // 状態の初回通知時点で始めると対象が0件のまま終わってしまう。
                startUnreadCatchUpOnce()
                if (!joinedRefreshStarted) {
                    joinedRefreshStarted = true
                    refreshJoinedChannels()
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
        ChannelLocalStore.recordChannelCreate(event, meta, relayUrl)
        if (event.id in joinedIds) ChannelLocalStore.setFavorite(event.id, true)
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
                    ChannelLocalStore.deleteChannel(dialog.channelId)
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
                ChannelLocalStore.deleteChannel(dialog.channelId)
                val s = _state.value as? UiState.Ready ?: return@launch
                _state.value = s.copy(deleteDialog = null)
            }
        }
    }

    /**
     * ★は「参加」として kind 10005 と同期する(2026-09-24 ユーザー判断)。表示は即時に切り替え、
     * 送信に失敗したら戻す。ログインしていない場合は従来どおり端末内の★だけを切り替える。
     */
    fun toggleFavorite(channelId: String) {
        if (relayUrl == null) return
        val current = _state.value as? UiState.Ready ?: return
        val item = current.channels.firstOrNull { it.event.id == channelId } ?: return
        val newFavorite = !item.isFavorite
        launch {
            ChannelLocalStore.setFavorite(channelId, newFavorite)
            val repository = joinedChannels ?: return@launch
            val relayHint = cachedChannels[channelId]?.meta?.relays?.firstOrNull() ?: relayUrl
            repository.setJoined(channelId, newFavorite, relayHint)
                .onSuccess { joined -> joinedIds = joined }
                .onFailure { error ->
                    ChannelLocalStore.setFavorite(channelId, !newFavorite)
                    val s = _state.value as? UiState.Ready ?: return@onFailure
                    _state.value = s.copy(notice = "参加状態を同期できませんでした: ${error.message ?: "不明なエラー"}")
                }
        }
    }

    /** 他端末・他クライアントで参加したチャンネルを★にする。端末内だけの★は外さない(意図しない公開を避ける)。 */
    private fun refreshJoinedChannels() {
        val repository = joinedChannels ?: return
        launch {
            val joined = repository.refresh().getOrNull() ?: return@launch
            joinedIds = joined
            joined.forEach { channelId ->
                if (ChannelLocalStore.get(channelId)?.hasMetadata == true) {
                    ChannelLocalStore.setFavorite(channelId, true)
                } else if (requestedNewMetaIds.add(channelId)) {
                    // 一覧に無い参加中チャンネルは kind 40 を取得してから★を付ける(acceptChannel)。
                    pendingNewMetaIds.add(channelId)
                }
            }
            scheduleNewMetaSubscription()
        }
    }

    fun showDetailDialog(channelId: String) {
        val current = _state.value as? UiState.Ready ?: return
        val item = current.channels.firstOrNull { it.event.id == channelId } ?: return
        detailDialogJob?.cancel()
        val initialEvents = item.event.takeIf { it.content.isNotBlank() || it.sig.isNotBlank() }
            ?.let(::listOf)
            .orEmpty()
        _state.value = current.copy(
            detailDialog = DetailDialogState(
                channelId = channelId,
                channelName = item.meta.name.ifBlank { "（名前なし）" },
                events = initialEvents,
            ),
        )
        detailDialogJob = launch {
            val events = linkedMapOf<String, NostrEvent>()
            initialEvents.forEach { events[it.id] = it }
            val completed = fetch(
                prefix = "ch-list-detail-$relayKey",
                filters = listOf(
                    NostrFilter(ids = listOf(channelId), kinds = listOf(40)),
                    NostrFilter(
                        authors = listOf(item.event.pubkey),
                        kinds = listOf(41),
                        eTags = listOf(channelId),
                        limit = 100,
                    ),
                ),
            ) { event ->
                if (ChannelMetadataResolver.acceptsCandidate(channelId, item.event.pubkey, event)) {
                    events[event.id] = event
                    updateDetailDialog(channelId, events.values.toList(), isLoading = true)
                }
            }
            updateDetailDialog(
                channelId = channelId,
                events = events.values.toList(),
                isLoading = false,
                error = if (completed) null else "リレーからの取得が完了しませんでした",
            )
        }
    }

    fun dismissDetailDialog() {
        detailDialogJob?.cancel()
        detailDialogJob = null
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(detailDialog = null)
    }

    private fun updateDetailDialog(
        channelId: String,
        events: List<NostrEvent>,
        isLoading: Boolean,
        error: String? = null,
    ) {
        val current = _state.value as? UiState.Ready ?: return
        val dialog = current.detailDialog?.takeIf { it.channelId == channelId } ?: return
        _state.value = current.copy(
            detailDialog = dialog.copy(
                events = events.sortedWith(
                    compareBy<NostrEvent> { it.kind }
                        .thenByDescending { it.createdAt }
                        .thenByDescending { it.id },
                ),
                isLoading = isLoading,
                error = error,
            ),
        )
    }

    fun showCreateDialog() {
        val current = _state.value as? UiState.Ready ?: return
        createPictureUploadJob?.cancel()
        createPictureUploadJob = null
        // 推奨リレーの初期選択はユーザーの書き込みリレー(FR-04)。
        val writable = normalizeRelayUrls(RelayStore.writableRelayUrlsSnapshot(), limit = Int.MAX_VALUE)
        _state.value = current.copy(
            createDialog = CreateDialogState(
                sessionId = ++createSessionSequence,
                candidateRelays = writable,
                selectedRelays = writable.take(MAX_RECOMMENDED_RELAYS).toSet(),
            ),
        )
    }

    fun dismissCreateDialog() {
        val current = _state.value as? UiState.Ready ?: return
        if (current.createDialog?.isCreating == true) return
        createPictureUploadJob?.cancel()
        createPictureUploadJob = null
        _state.value = current.copy(createDialog = null)
    }

    fun consumeCreatedChannelNavigation() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(createdChannelIdToOpen = null)
    }

    fun consumeNotice() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(notice = null)
    }

    fun onCreateNameChange(name: String) = updateCreateDialog { it.copy(name = name, error = null) }

    fun onCreateAboutChange(about: String) = updateCreateDialog { it.copy(about = about, error = null) }

    fun onCreateRelaySelectionChange(selected: Set<String>) = updateCreateDialog { dialog ->
        if (selected.size > MAX_RECOMMENDED_RELAYS) {
            dialog.copy(error = "推奨リレーは${MAX_RECOMMENDED_RELAYS}件までです")
        } else {
            dialog.copy(selectedRelays = selected, error = null)
        }
    }

    /** 手入力の推奨リレーを追加して選択する。戻り値はエラー文言(FR-01 の検証)。 */
    fun addCreateCustomRelay(raw: String): String? {
        val dialog = (_state.value as? UiState.Ready)?.createDialog ?: return null
        val url = normalizeRelayUrl(raw) ?: return "wss:// または ws:// で始まるリレーURLを入力してください"
        if (url !in dialog.selectedRelays && dialog.selectedRelays.size >= MAX_RECOMMENDED_RELAYS) {
            return "推奨リレーは${MAX_RECOMMENDED_RELAYS}件までです"
        }
        updateCreateDialog {
            it.copy(
                candidateRelays = if (url in it.candidateRelays) it.candidateRelays else it.candidateRelays + url,
                selectedRelays = it.selectedRelays + url,
                error = null,
            )
        }
        return null
    }

    fun onCreatePictureSelected(bytes: ByteArray, mimeType: String) {
        val dialog = (_state.value as? UiState.Ready)?.createDialog ?: return
        if (dialog.isUploadingPicture || dialog.isRetryingFirstPost) return
        val sessionId = dialog.sessionId
        updateCreateDialog { it.copy(isUploadingPicture = true, error = null) }
        createPictureUploadJob = launch {
            withContext(Dispatchers.Default) {
                ImageUploader.uploadMedia(bytes, mimeType, accountSession?.signer)
            }.onSuccess { metadata ->
                updateCreateDialogForSession(sessionId) { it.copy(picture = metadata.url, isUploadingPicture = false) }
            }.onFailure { error ->
                updateCreateDialogForSession(sessionId) {
                    it.copy(isUploadingPicture = false, error = "アイコン画像のアップロードに失敗しました: ${error.message}")
                }
            }
        }
    }

    fun onCreatePictureRemoved() = updateCreateDialog { it.copy(picture = "") }

    private fun updateCreateDialog(transform: (CreateDialogState) -> CreateDialogState) {
        val current = _state.value as? UiState.Ready ?: return
        val dialog = current.createDialog ?: return
        _state.value = current.copy(createDialog = transform(dialog))
    }

    private fun updateCreateDialogForSession(
        sessionId: Long,
        transform: (CreateDialogState) -> CreateDialogState,
    ) {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.withCreateDialogSession(sessionId, transform)
    }

    private suspend fun recordCreateRelayResult(
        event: NostrEvent,
        meta: ChannelMeta?,
        sessionId: Long,
        kind: Int,
        targets: List<String>,
        result: RelayPublishResult,
    ) {
        if (meta != null) {
            result.succeededRelays.forEach { url -> ChannelLocalStore.recordChannelCreate(event, meta, url) }
        }
        withContext(Dispatchers.Main) {
            val next = (createPublishStates[event.id] ?: ChannelPublishUiState.sending(targets))
                .withRelayResult(result)
            createPublishStates[event.id] = next
            val ready = _state.value as? UiState.Ready ?: return@withContext
            val dialog = ready.createDialog
            if (dialog?.sessionId == sessionId && dialog.activePublishKind == kind) {
                _state.value = ready.copy(createDialog = dialog.copy(publishState = next))
            } else if (next.phase == ChannelPublishUiState.Phase.PartialSuccess) {
                reportPartialCreate(event, kind, next)
            }
        }
    }

    private fun reportPartialCreate(event: NostrEvent, kind: Int, state: ChannelPublishUiState) {
        if (state.phase != ChannelPublishUiState.Phase.PartialSuccess ||
            !reportedPartialCreateEvents.add(event.id)) return
        ChannelCreateNoticeStore.enqueue(ChannelCreateNoticeStore.Notice(
            eventId = event.id,
            pubkey = event.pubkey,
            message = if (kind == 40) "チャンネル作成: ${state.summary}" else "最初の投稿: ${state.summary}",
        ))
    }

    /**
     * kind 40 を作成し、[firstPost] があれば最初の kind 42 を送る(FR-13、第16.11節)。
     * 送信先は推奨リレー + ユーザーの書き込みリレー、kind 42 の relay hint は推奨リレーの先頭。
     */
    internal fun createChannel(firstPost: ComposedNote? = null) {
        val current = _state.value as? UiState.Ready ?: return
        val dialog = current.createDialog ?: return
        if (!dialog.canSubmit) return
        val plan = ChannelCreatePlan.from(dialog.recommendedRelays, RelayStore.entries.value)
        updateCreateDialog {
            it.copy(isCreating = true, error = null, activePublishKind = if (dialog.isRetryingFirstPost) 42 else 40,
                publishState = ChannelPublishUiState.sending(plan.targets))
        }
        launch {
            val publisher = SignedEventPublisher(accountSession?.signer)
            val channelEvent = dialog.createdChannel ?: run {
                val meta = ChannelMeta(
                    name = dialog.name.trim(),
                    about = dialog.about.trim(),
                    picture = dialog.picture,
                    relays = plan.recommendedRelays,
                )
                val result = publisher.publish(
                    content = meta.toChannelContent(),
                    kind = 40,
                    tags = listOf(listOf("client", "ToriNos")),
                    relayUrls = plan.relayUrls,
                    onEventRelayResult = { event, relayResult ->
                        recordCreateRelayResult(event, meta, dialog.sessionId, 40, plan.targets, relayResult)
                    },
                )
                val publishState = ChannelPublishUiState.from(plan.targets, result)
                when (result) {
                    is SignedPublishResult.Published -> {
                        acceptCreatedChannel(result.event, meta, result.relayResult.succeededRelays)
                        reportPartialCreate(result.event, 40, createPublishStates[result.event.id] ?: publishState)
                        result.event
                    }
                    SignedPublishResult.MissingSigner -> return@launch failCreate("秘密鍵が設定されていません", publishState)
                    is SignedPublishResult.Failed -> return@launch failCreate(
                        publishState.summary ?: result.cause.message ?: "作成に失敗しました",
                        publishState,
                    )
                }
            }
            if (firstPost != null) {
                updateCreateDialogForSession(dialog.sessionId) {
                    it.copy(activePublishKind = 42, publishState = ChannelPublishUiState.sending(plan.targets))
                }
                // 初回投稿は kind 40 と同じ送信先と relay hint を使う(第16.11節 手順4)。
                val postResult = publisher.publish(
                    content = firstPost.content,
                    kind = 42,
                    tags = ChannelEventTags.rootMessage(channelEvent.id, plan.primaryHint) +
                        firstPost.tags + listOf(listOf("client", "ToriNos")),
                    relayUrls = plan.relayUrls,
                    onEventRelayResult = { event, relayResult ->
                        recordCreateRelayResult(event, null, dialog.sessionId, 42, plan.targets, relayResult)
                    },
                )
                val publishState = ChannelPublishUiState.from(plan.targets, postResult)
                if (postResult !is SignedPublishResult.Published) {
                    val reason = (postResult as? SignedPublishResult.Failed)?.let {
                        publishState.summary ?: it.cause.message
                    } ?: "秘密鍵が設定されていません"
                    val s = _state.value as? UiState.Ready ?: return@launch
                    _state.value = s.copy(
                        channels = buildChannelList(),
                        createDialog = s.createDialog?.copy(
                            isCreating = false,
                            createdChannel = channelEvent,
                            publishState = publishState,
                            error = "チャンネルは作成しました。最初の投稿を送信できませんでした（$reason）",
                        ),
                    )
                    return@launch
                }
                seenMessageIds.add(postResult.event.id)
                updateActivity(postResult.event, channelEvent.id)
                reportPartialCreate(postResult.event, 42, createPublishStates[postResult.event.id] ?: publishState)
            }
            val s = _state.value as? UiState.Ready ?: return@launch
            _state.value = s.copy(
                channels = buildChannelList(),
                createDialog = null,
                createdChannelIdToOpen = channelEvent.id,
            )
        }
    }

    private suspend fun acceptCreatedChannel(event: NostrEvent, meta: ChannelMeta, succeededRelays: Set<String>) {
        channelMap[event.id] = ChannelItem(event, meta)
        lastActivities[event.id] = event.createdAt
        // 受理したリレーを観測元として記録する。推奨リレー(content.relays)とは別に保持される。
        succeededRelays.ifEmpty { setOfNotNull(relayUrl) }.forEach { url ->
            ChannelLocalStore.recordChannelCreate(event, meta, url)
        }
        scheduleAuthorSubscription()
    }

    private fun failCreate(message: String, publishState: ChannelPublishUiState) {
        val s = _state.value as? UiState.Ready ?: return
        _state.value = s.copy(
            createDialog = s.createDialog?.copy(isCreating = false, error = message, publishState = publishState),
        )
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
        // 全履歴は保存しない。一覧プレビュー用の直近1件だけを端末へ残す(第16.12節)。
        launch { ChannelLocalStore.recordLatestMessage(channelId, event) }
    }

    private suspend fun currentLastReadAts(relayUrl: String): Map<String, Long?> =
        ChannelLocalStore.observe(relayUrl).first().associate { it.channelId to it.lastReadAt }

    private fun <K, V : Any> Map<K, V?>.filterValuesNotNull(): Map<K, V> =
        mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

    /** 選択中リレーへ既読チャンネルの新着を一括問い合わせし、未読件数の起点を作る(第16.12.1節)。 */
    private fun startUnreadCatchUpOnce() {
        if (catchUpStarted || relayUrl == null) return
        catchUpStarted = true
        launch {
            // collector 経由の cachedChannels は反映が遅れうるため、ストアから直接読む。
            val lastReadAts = currentLastReadAts(relayUrl).filterValuesNotNull()
            if (lastReadAts.isEmpty()) return@launch
            val relayMaxLimit = withTimeoutOrNull(RELAY_INFO_TIMEOUT_MS) {
                RelayInformationRepository.fetch(relayUrl).getOrNull()?.limitation?.maxLimit
            }
            val limit = ChannelUnreadCatchUp.effectiveLimit(relayMaxLimit)
            val pending = ArrayDeque(ChannelUnreadCatchUp.plan(lastReadAts))
            var requests = 0
            while (pending.isNotEmpty()) {
                val chunk = pending.removeFirst()
                requests++
                val events = mutableListOf<NostrEvent>()
                val completed = fetch(catchUpSubId, NostrFilter(
                    kinds = listOf(42),
                    eTags = chunk.channelIds,
                    since = chunk.since,
                    until = liveSince - 1,
                    limit = limit,
                )) { event -> if (event.kind == 42) events += event }
                // 失敗・タイムアウトしたチャンクは前回値を維持する(第16.12.5節)。
                if (!completed) continue
                val results = ChannelUnreadCatchUp.tally(chunk, events, lastReadAts, limit) { it.channelIdFromMessage() }
                val retry = if (results.values.any { it.isLowerBound }) {
                    ChannelUnreadCatchUp.split(chunk, lastReadAts)
                } else {
                    emptyList()
                }
                // 分割後の要求が予算内に収まる場合だけ再問い合わせし、この回の下限値は捨てる。
                if (retry.isNotEmpty() && requests + pending.size + retry.size <= ChannelUnreadCatchUp.MAX_REQUESTS) {
                    retry.asReversed().forEach(pending::addFirst)
                } else {
                    unreadTracker.applyCatchUp(results, currentLastReadAts(relayUrl))
                }
                events.groupBy { it.channelIdFromMessage() }.forEach { (channelId, channelEvents) ->
                    val newest = channelEvents.maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
                    if (channelId != null && newest != null) updateActivity(newest, channelId)
                }
                emitReady()
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
            deleteDialog = current?.deleteDialog,
            notice = current?.notice,
            detailDialog = current?.detailDialog,
            createdChannelIdToOpen = current?.createdChannelIdToOpen,
            canLoadMore = hasMoreChannels,
            isLoadingMore = loadingMore,
        )
    }

    private suspend fun fetch(
        prefix: String,
        filter: NostrFilter,
        onEvent: suspend (NostrEvent) -> Unit,
    ): Boolean = fetch(prefix, listOf(filter), onEvent)

    private suspend fun fetch(
        prefix: String,
        filters: List<NostrFilter>,
        onEvent: suspend (NostrEvent) -> Unit,
    ): Boolean = fetchChannelEvents(
        SubscriptionSpec(
            id = "$prefix-${requestSequence++}",
            filters = filters,
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
                    // 未読件数はキャッチアップで数えるため、ここではプレビュー用の最新1件だけを取る。
                    val since = cachedChannels[channelId]?.latestMessage?.createdAt
                    fetch(activitySubId, NostrFilter(
                        kinds = listOf(42), eTags = listOf(channelId), since = since, limit = 1,
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
                cachedChannels.values.mapNotNull { it.latestMessage?.pubkey }
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
                    val cachedLatest = cached?.latestMessage
                    val lastActivityAt = listOfNotNull(
                        lastActivities[channelId],
                        cachedLatest?.createdAt,
                    ).maxOrNull()
                    // ライブで受けた本文(カスタム絵文字タグ付き)が端末保存のプレビューより新しければそちらを使う。
                    val useCachedPreview = cachedLatest != null &&
                        (it.latestMessagePreview == null || cachedLatest.createdAt > (lastActivities[channelId] ?: 0))
                    val latestAuthor = if (useCachedPreview) cachedLatest.pubkey else it.latestMessageAuthorPubkey
                    val badge = unreadTracker.badge(channelId, cached?.lastReadAt)
                    it.copy(
                        // kind 41 で更新された実効メタデータは kind 40 の元値より優先する。
                        meta = cached?.takeIf { state -> state.metadataKind == 41 }?.meta ?: it.meta,
                        authorProfile = authorProfiles[it.event.pubkey],
                        lastActivityAt = lastActivityAt,
                        latestMessageAuthorPubkey = latestAuthor,
                        latestMessageAuthorProfile = latestAuthor?.let { pubkey -> authorProfiles[pubkey] },
                        latestMessagePreview = if (useCachedPreview) cachedLatest.contentPreview else it.latestMessagePreview,
                        latestMessageCustomEmojis = if (useCachedPreview) emptyMap() else it.latestMessageCustomEmojis,
                        unreadCount = badge.count,
                        unreadCountIsLowerBound = badge.isLowerBound,
                        hasNewActivity = badge.hasNewActivity,
                        hasBeenOpened = cached?.lastReadAt != null,
                        isFavorite = cached?.isFavorite ?: false,
                    )
                }
            }
            .sortedWith(
                compareBy<ChannelItem> { it.lastActivityAt == null }
                    .thenByDescending { it.lastActivityAt ?: it.event.createdAt },
            )

    private fun ChannelLocalState.toChannelItem(): ChannelItem =
        ChannelItem(
            event = NostrEvent(
                id = channelId,
                pubkey = ownerPubkey,
                createdAt = channelCreatedAt,
                kind = 40,
                tags = emptyList(),
                content = "",
                sig = "",
            ),
            meta = meta,
            lastActivityAt = latestMessage?.createdAt,
            latestMessageAuthorPubkey = latestMessage?.pubkey,
            latestMessagePreview = latestMessage?.contentPreview,
            isFavorite = isFavorite,
        )

    private fun NostrEvent.channelIdFromMessage(): String? =
        tags
            .firstOrNull { it.firstOrNull() == "e" && it.getOrNull(3) == "root" }
            ?.getOrNull(1)
            ?: tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)

    override fun onCleared() {
        activityQueue.stop()
        createPictureUploadJob?.cancel()
        jobs.forEach { it.cancel() }
        newMetaJob?.cancel()
        authorSubscriptionJob?.cancel()
        detailDialogJob?.cancel()
        NostrRepository.close(liveSubId)
        super.onCleared()
    }
}

internal fun ChannelListViewModel.UiState.Ready.withCreateDialogSession(
    sessionId: Long,
    transform: (ChannelListViewModel.CreateDialogState) -> ChannelListViewModel.CreateDialogState,
): ChannelListViewModel.UiState.Ready {
    val dialog = createDialog?.takeIf { it.sessionId == sessionId } ?: return this
    return copy(createDialog = transform(dialog))
}
