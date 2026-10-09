package com.nostr.torinos.ui.relay

import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.network.FollowedRelayDiscovery
import com.nostr.torinos.network.FollowedRelayDiscoveryResult
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayInformation
import com.nostr.torinos.network.RelayInformationRepository
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.network.normalizeRelayUrl
import com.nostr.torinos.network.writableRelayUrls
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update

data class RelayInformationUiState(
    val relayUrl: String? = null,
    val isLoading: Boolean = false,
    val information: RelayInformation? = null,
    val errorMessage: String? = null,
)

data class FollowedRelayDiscoveryUiState(
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

data class PublishedRelayListUiState(
    val isLoading: Boolean = false,
    val isAwaitingFirstResponse: Boolean = false,
    val publishedUrls: Set<String> = emptySet(),
    val hasCompletedInitialFetch: Boolean = false,
    val hasPublishedEvent: Boolean = false,
    val pendingAdditions: Set<String> = emptySet(),
    val pendingRemovals: Set<String> = emptySet(),
    val pendingUpdates: Set<String> = emptySet(),
    /** 確定後に送信できるリレーが残るか。0件になる変更は確定させない。 */
    val hasWritableRelay: Boolean = true,
    val isPublishing: Boolean = false,
    val message: String? = null,
    val failedRelayUrls: List<String> = emptyList(),
    val errorMessage: String? = null,
) {
    val hasChanges: Boolean
        get() = pendingAdditions.isNotEmpty() || pendingRemovals.isNotEmpty() || pendingUpdates.isNotEmpty()
    val changeCount: Int get() = pendingAdditions.size + pendingRemovals.size + pendingUpdates.size
    val canPublishChanges: Boolean
        get() = hasCompletedInitialFetch && hasChanges && hasWritableRelay && !isPublishing
}

internal fun initialPublishedRelayListUiState(canSyncPublishedRelayList: Boolean) =
    PublishedRelayListUiState(
        isLoading = canSyncPublishedRelayList,
        isAwaitingFirstResponse = canSyncPublishedRelayList,
        hasCompletedInitialFetch = !canSyncPublishedRelayList,
    )

class RelaySettingsViewModel(
    private val accountSession: AccountSession? = null,
) : SafeViewModel() {
    private val canSyncPublishedRelayList = accountSession != null && isWriteSupported
    val isAnonymous = accountSession == null
    private val _entries = MutableStateFlow(RelayStore.entries.value)
    val entries: StateFlow<List<RelayEntry>> = _entries.asStateFlow()
    private val _informationState = MutableStateFlow(RelayInformationUiState())
    val informationState: StateFlow<RelayInformationUiState> = _informationState.asStateFlow()
    private val _relayInformation = MutableStateFlow<Map<String, RelayInformation>>(emptyMap())
    val relayInformation: StateFlow<Map<String, RelayInformation>> = _relayInformation.asStateFlow()
    val connectionStates = NostrRepository.relayConnectionStates
    private val _discoveryState = MutableStateFlow(FollowedRelayDiscoveryUiState())
    val discoveryState: StateFlow<FollowedRelayDiscoveryUiState> = _discoveryState.asStateFlow()
    private val _publishedRelayListState = MutableStateFlow(
        initialPublishedRelayListUiState(canSyncPublishedRelayList),
    )
    val publishedRelayListState: StateFlow<PublishedRelayListUiState> =
        _publishedRelayListState.asStateFlow()
    private val _relayUsers = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** リレーごとの、そのリレーを NIP-65 に載せているフォロー先の pubkey。 */
    val relayUsers: StateFlow<Map<String, List<String>>> = _relayUsers.asStateFlow()

    private var fetchInformationJob: Job? = null
    private val relayInformationJobs = mutableMapOf<String, Job>()
    private var discoverFollowedRelaysJob: Job? = null
    private val changedRelayUrls = mutableSetOf<String>()
    private var committedEntries = RelayStore.entries.value
    private var isApplyingDraft = false

    init {
        observeStoredEntries()
        loadRelayInformation(_entries.value.mapTo(linkedSetOf()) { it.url })
        if (accountSession != null) {
            launch { loadRelayUsers() }
            discoverFollowedRelays()
        }
        if (canSyncPublishedRelayList) refreshPublishedRelayList()
    }

    fun add(url: String) {
        if (_publishedRelayListState.value.isPublishing) return
        val normalized = relayUrlFromInput(url) ?: return
        val existing = entries.value.firstOrNull { it.url == normalized }
        if (existing != null) {
            // 候補として登録済みのリレーは、追加操作で有効化する。
            if (!existing.enabled) setEnabled(normalized, true)
            return
        }
        _entries.update { it + RelayEntry(normalized, enabled = true) }
        loadRelayInformation(setOf(normalized))
        changedRelayUrls += normalized
        reconcilePublishedRelayChanges()
    }

    fun remove(url: String) {
        if (_publishedRelayListState.value.isPublishing) return
        if (entries.value.none { it.url == url }) return
        _entries.update { list -> list.filterNot { it.url == url } }
        changedRelayUrls += url
        reconcilePublishedRelayChanges()
    }

    fun setEnabled(url: String, enabled: Boolean) {
        if (_publishedRelayListState.value.isPublishing) return
        if (entries.value.firstOrNull { it.url == url }?.enabled == enabled) return
        _entries.update { list -> setRelayEnabledInDraft(list, committedEntries, url, enabled) }
        changedRelayUrls += url
        reconcilePublishedRelayChanges()
    }

    /** 受信・送信の使い方を変える。両方とも外す操作は受け付けない。 */
    fun setReadWrite(url: String, read: Boolean, write: Boolean) {
        if (_publishedRelayListState.value.isPublishing) return
        if (!read && !write) return
        val current = entries.value.firstOrNull { it.url == url } ?: return
        if (current.read == read && current.write == write) return
        _entries.update { list ->
            list.map { entry -> if (entry.url == url) entry.copy(read = read, write = write) else entry }
        }
        changedRelayUrls += url
        reconcilePublishedRelayChanges()
    }

    /** 一つのリレーの未確定の変更だけを取り消す。 */
    fun revertChange(url: String) {
        if (_publishedRelayListState.value.isPublishing) return
        val committed = committedEntries.firstOrNull { it.url == url }
        _entries.update { list ->
            if (committed == null) {
                list.filterNot { it.url == url }
            } else {
                list.map { entry -> if (entry.url == url) committed else entry }
            }
        }
        changedRelayUrls -= url
        reconcilePublishedRelayChanges()
    }

    fun publishRelayListChanges() {
        val state = _publishedRelayListState.value
        if (!state.canPublishChanges) return
        val draftEntries = entries.value
        val changedUrls = changedRelayUrls.toSet()
        _publishedRelayListState.value = state.copy(
            isPublishing = true,
            message = null,
            failedRelayUrls = emptyList(),
            errorMessage = null,
        )
        launch {
            try {
                val result = if (canSyncPublishedRelayList) {
                    checkNotNull(accountSession) { "アカウントセッションがありません" }
                        .relayListSynchronizer.updatePublishedRelayList(
                        additions = state.pendingAdditions.mapNotNull { url ->
                            draftEntries.firstOrNull { it.url == url }
                        },
                        removals = state.pendingRemovals,
                        updates = state.pendingUpdates.mapNotNull { url ->
                            draftEntries.firstOrNull { it.url == url }
                        },
                    )
                } else {
                    null
                }
                isApplyingDraft = true
                val appliedEntries = RelayStore.applyEntryChanges(changedUrls, draftEntries)
                committedEntries = appliedEntries
                _entries.value = appliedEntries
                changedRelayUrls.clear()
                _publishedRelayListState.value = PublishedRelayListUiState(
                    publishedUrls = result?.event?.tags
                        ?.mapNotNull { tag -> tag.getOrNull(1)?.takeIf { tag.firstOrNull() == "r" } }
                        ?.toSet()
                        ?: state.publishedUrls,
                    hasCompletedInitialFetch = true,
                    hasPublishedEvent = result != null || state.hasPublishedEvent,
                    failedRelayUrls = result?.publishResult?.failedRelays?.keys?.toList().orEmpty(),
                    message = if (result == null) {
                        "リレー設定を更新しました"
                    } else if (result.publishResult.failureCount == 0) {
                        "公開リレーリストを更新しました"
                    } else {
                        "更新しました（${result.publishResult.failureCount}件のリレーへ送信できませんでした）"
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _publishedRelayListState.update {
                    it.copy(
                        isPublishing = false,
                        errorMessage = e.message ?: "公開リレーリストを更新できませんでした",
                    )
                }
            } finally {
                isApplyingDraft = false
            }
        }
    }

    fun cancelRelayListChanges() {
        if (_publishedRelayListState.value.isPublishing) return
        val storedEntries = RelayStore.entries.value
        committedEntries = storedEntries
        _entries.value = storedEntries
        changedRelayUrls.clear()
        _publishedRelayListState.update {
            it.copy(
                pendingAdditions = emptySet(),
                pendingRemovals = emptySet(),
                pendingUpdates = emptySet(),
                message = null,
                failedRelayUrls = emptyList(),
                errorMessage = null,
            )
        }
    }

    fun refreshPublishedRelayList() {
        if (!canSyncPublishedRelayList) return
        val state = _publishedRelayListState.value
        if ((state.isLoading && state.publishedUrls.isNotEmpty()) || state.isPublishing) return
        _publishedRelayListState.value = state.copy(
            isLoading = true,
            isAwaitingFirstResponse = true,
            hasCompletedInitialFetch = false,
            errorMessage = null,
        )
        launch {
            try {
                val snapshot = checkNotNull(accountSession) { "アカウントセッションがありません" }
                    .relayListSynchronizer.fetchPublishedRelayList(
                    onFirstResponse = {
                        _publishedRelayListState.update {
                            it.copy(isAwaitingFirstResponse = false)
                        }
                    },
                )
                _publishedRelayListState.value = PublishedRelayListUiState(
                    publishedUrls = snapshot.urls,
                    hasCompletedInitialFetch = true,
                    hasPublishedEvent = snapshot.hasPublishedEvent,
                )
                reconcilePublishedRelayChanges()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _publishedRelayListState.update {
                    it.copy(
                        isLoading = false,
                        isAwaitingFirstResponse = false,
                        errorMessage = e.message ?: "公開リレーリストを取得できませんでした",
                    )
                }
            }
        }
    }

    private fun reconcilePublishedRelayChanges() {
        val changes = calculatePublishedRelayListChanges(
            changedUrls = changedRelayUrls,
            committedEntries = committedEntries,
            draftEntries = entries.value,
        )
        changedRelayUrls.retainAll(changes.additions + changes.removals + changes.updates)
        _publishedRelayListState.update { state ->
            state.copy(
                pendingAdditions = changes.additions,
                pendingRemovals = changes.removals,
                pendingUpdates = changes.updates,
                hasWritableRelay = writableRelayUrls(entries.value).isNotEmpty(),
                message = null,
                failedRelayUrls = emptyList(),
            )
        }
    }

    private fun observeStoredEntries() {
        launch {
            RelayStore.entries.collect { storedEntries ->
                if (isApplyingDraft) return@collect
                loadRelayInformation(storedEntries.mapTo(linkedSetOf()) { it.url })
                if (changedRelayUrls.isEmpty()) {
                    committedEntries = storedEntries
                    _entries.value = storedEntries
                } else {
                    val rebasedDraft = rebaseRelayEntryChanges(
                        changedUrls = changedRelayUrls,
                        newCommittedEntries = storedEntries,
                        currentDraftEntries = _entries.value,
                    )
                    committedEntries = storedEntries
                    _entries.value = rebasedDraft
                    reconcilePublishedRelayChanges()
                }
            }
        }
    }

    fun showRelayInformation(url: String) {
        fetchRelayInformation(url = url, forceRefresh = false)
    }

    fun refreshRelayInformation() {
        val url = _informationState.value.relayUrl ?: return
        fetchRelayInformation(url = url, forceRefresh = true)
    }

    fun dismissRelayInformation() {
        fetchInformationJob?.cancel()
        fetchInformationJob = null
        _informationState.value = RelayInformationUiState()
    }

    fun retryFollowedRelayDiscovery() {
        discoverFollowedRelays(forceRefresh = true)
    }

    private fun discoverFollowedRelays(forceRefresh: Boolean = false) {
        if (discoverFollowedRelaysJob?.isActive == true) return
        _discoveryState.value = FollowedRelayDiscoveryUiState(isLoading = true)
        discoverFollowedRelaysJob = launch {
            try {
                val session = checkNotNull(accountSession) { "アカウントセッションがありません" }
                val result = FollowedRelayDiscovery.discover(
                    ownPubkey = session.pubkey,
                    followRepository = session.followRepository,
                    relayStore = session.relayStore,
                    forceRefresh = forceRefresh,
                )
                _discoveryState.value = when (result) {
                    is FollowedRelayDiscoveryResult.Completed -> FollowedRelayDiscoveryUiState(
                        message = result.addedCount
                            .takeIf { it > 0 }
                            ?.let { "フォロー先のリレーをリレー一覧に $it 件追加しました" },
                    )
                    FollowedRelayDiscoveryResult.NothingToFetch -> FollowedRelayDiscoveryUiState()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _discoveryState.value = FollowedRelayDiscoveryUiState(
                    errorMessage = e.message ?: "フォロー先のリレーを取得できませんでした",
                )
            }
            loadRelayUsers()
        }
    }

    private suspend fun loadRelayUsers() {
        val session = accountSession ?: return
        try {
            _relayUsers.value = FollowedRelayDiscovery.relayUsers(
                ownPubkey = session.pubkey,
                followedPubkeys = session.followRepository.followedPubkeys.value,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 人数は並び替えの手がかりなので、読めなくても一覧は表示する。
        }
    }

    private fun fetchRelayInformation(url: String, forceRefresh: Boolean) {
        fetchInformationJob?.cancel()
        _informationState.value = RelayInformationUiState(relayUrl = url, isLoading = true)
        fetchInformationJob = launch {
            val result = RelayInformationRepository.fetch(url, forceRefresh)
            _informationState.update { current ->
                if (current.relayUrl != url) {
                    current
                } else {
                    result.fold(
                        onSuccess = {
                            _relayInformation.update { information -> information + (url to it) }
                            current.copy(isLoading = false, information = it, errorMessage = null)
                        },
                        onFailure = {
                            current.copy(
                                isLoading = false,
                                information = null,
                                errorMessage = it.message ?: "リレー情報を取得できませんでした",
                            )
                        },
                    )
                }
            }
        }
    }

    private fun loadRelayInformation(urls: Set<String>) {
        urls.forEach { url ->
            if (url in _relayInformation.value || relayInformationJobs[url]?.isActive == true) return@forEach
            relayInformationJobs[url] = launch {
                try {
                    RelayInformationRepository.fetch(url).onSuccess { information ->
                        _relayInformation.update { current -> current + (url to information) }
                    }
                } finally {
                    relayInformationJobs.remove(url)
                }
            }
        }
    }

}

internal data class PublishedRelayListChanges(
    val additions: Set<String>,
    val removals: Set<String>,
    val updates: Set<String> = emptySet(),
)

internal fun calculatePublishedRelayListChanges(
    changedUrls: Set<String>,
    committedEntries: List<RelayEntry>,
    draftEntries: List<RelayEntry>,
): PublishedRelayListChanges {
    val committedByUrl = committedEntries.associateBy { it.url }
    val draftByUrl = draftEntries.associateBy { it.url }
    return PublishedRelayListChanges(
        additions = changedUrls.filterTo(linkedSetOf()) { url ->
            val committed = committedByUrl[url]
            val draft = draftByUrl[url]
            draft != null && draft.enabled && (committed == null || !committed.enabled)
        },
        removals = changedUrls.filterTo(linkedSetOf()) { url ->
            val committed = committedByUrl[url]
            val draft = draftByUrl[url]
            committed != null && (draft == null || (committed.enabled && !draft.enabled))
        },
        updates = changedUrls.filterTo(linkedSetOf()) { url ->
            val committed = committedByUrl[url]
            val draft = draftByUrl[url]
            committed != null && draft != null && committed.enabled && draft.enabled &&
                (committed.read != draft.read || committed.write != draft.write)
        },
    )
}

/** 下書きでリレーの使用を切り替える。保存前に追加したリレーを外すときは、追加自体を取り消す。 */
internal fun setRelayEnabledInDraft(
    draftEntries: List<RelayEntry>,
    committedEntries: List<RelayEntry>,
    url: String,
    enabled: Boolean,
): List<RelayEntry> =
    if (!enabled && committedEntries.none { it.url == url }) {
        draftEntries.filterNot { it.url == url }
    } else {
        draftEntries.map { entry -> if (entry.url == url) entry.copy(enabled = enabled) else entry }
    }

/**
 * 入力欄の文字列を追加候補のリレー URL にする。
 * スキーム省略時は wss:// を補うが、検索語と区別するためドメインらしい入力に限る。
 */
internal fun relayUrlFromInput(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val hasScheme = trimmed.startsWith("wss://", ignoreCase = true) ||
        trimmed.startsWith("ws://", ignoreCase = true)
    if (!hasScheme && '.' !in trimmed.substringBefore('/')) return null
    return normalizeRelayUrl(if (hasScheme) trimmed else "wss://$trimmed")
}

/** 外部で更新された一覧へ、編集中の URL に対する操作意図だけを再適用する。 */
internal fun rebaseRelayEntryChanges(
    changedUrls: Set<String>,
    newCommittedEntries: List<RelayEntry>,
    currentDraftEntries: List<RelayEntry>,
): List<RelayEntry> {
    val draftByUrl = currentDraftEntries.associateBy { it.url }
    val committedUrls = newCommittedEntries.mapTo(hashSetOf()) { it.url }
    return buildList {
        newCommittedEntries.forEach { committed ->
            if (committed.url in changedUrls) {
                draftByUrl[committed.url]?.let(::add)
            } else {
                add(committed)
            }
        }
        currentDraftEntries.forEach { draft ->
            if (draft.url in changedUrls && draft.url !in committedUrls) add(draft)
        }
    }.distinctBy { it.url }
}
