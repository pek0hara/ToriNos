package com.nostr.torinos.ui.relay

import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.network.FollowedRelayDiscovery
import com.nostr.torinos.network.FollowedRelayDiscoveryResult
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayInformation
import com.nostr.torinos.network.RelayInformationRepository
import com.nostr.torinos.network.RelayListSynchronizer
import com.nostr.torinos.network.RelayStore
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
    val isPublishing: Boolean = false,
    val message: String? = null,
    val failedRelayUrls: List<String> = emptyList(),
    val errorMessage: String? = null,
) {
    val hasChanges: Boolean get() = pendingAdditions.isNotEmpty() || pendingRemovals.isNotEmpty()
    val canPublishChanges: Boolean get() = hasCompletedInitialFetch && hasChanges && !isPublishing
}

class RelaySettingsViewModel : SafeViewModel() {
    private val _entries = MutableStateFlow(RelayStore.entries.value)
    val entries: StateFlow<List<RelayEntry>> = _entries.asStateFlow()
    private val _informationState = MutableStateFlow(RelayInformationUiState())
    val informationState: StateFlow<RelayInformationUiState> = _informationState.asStateFlow()
    private val _discoveryState = MutableStateFlow(FollowedRelayDiscoveryUiState())
    val discoveryState: StateFlow<FollowedRelayDiscoveryUiState> = _discoveryState.asStateFlow()
    private val _publishedRelayListState = MutableStateFlow(
        PublishedRelayListUiState(
            isLoading = isWriteSupported,
            isAwaitingFirstResponse = isWriteSupported,
            hasCompletedInitialFetch = !isWriteSupported,
        ),
    )
    val publishedRelayListState: StateFlow<PublishedRelayListUiState> =
        _publishedRelayListState.asStateFlow()

    private var fetchInformationJob: Job? = null
    private var discoverFollowedRelaysJob: Job? = null
    private val changedRelayUrls = mutableSetOf<String>()
    private var committedEntries = RelayStore.entries.value
    private var isApplyingDraft = false

    init {
        observeStoredEntries()
        discoverFollowedRelays()
        if (isWriteSupported) refreshPublishedRelayList()
    }

    fun add(url: String) {
        if (_publishedRelayListState.value.isPublishing) return
        val trimmed = url.trim()
        if (trimmed.isBlank() || entries.value.any { it.url == trimmed }) return
        _entries.update { it + RelayEntry(trimmed, enabled = true) }
        changedRelayUrls += trimmed
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
        _entries.update { list ->
            list.map { entry -> if (entry.url == url) entry.copy(enabled = enabled) else entry }
        }
        changedRelayUrls += url
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
                val result = if (isWriteSupported) {
                    RelayListSynchronizer.updatePublishedRelayList(
                        additions = state.pendingAdditions,
                        removals = state.pendingRemovals,
                        requireExistingEvent = state.hasPublishedEvent,
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
                message = null,
                failedRelayUrls = emptyList(),
                errorMessage = null,
            )
        }
    }

    fun refreshPublishedRelayList() {
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
                val snapshot = RelayListSynchronizer.fetchPublishedRelayList(
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
        changedRelayUrls.retainAll(changes.additions + changes.removals)
        _publishedRelayListState.update { state ->
            state.copy(
                pendingAdditions = changes.additions,
                pendingRemovals = changes.removals,
                message = null,
                failedRelayUrls = emptyList(),
            )
        }
    }

    private fun observeStoredEntries() {
        launch {
            RelayStore.entries.collect { storedEntries ->
                if (!isApplyingDraft && changedRelayUrls.isEmpty()) {
                    committedEntries = storedEntries
                    _entries.value = storedEntries
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
                val result = FollowedRelayDiscovery.discover(forceRefresh)
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

}

internal data class PublishedRelayListChanges(
    val additions: Set<String>,
    val removals: Set<String>,
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
            draft != null && (committed == null || (!committed.enabled && draft.enabled))
        },
        removals = changedUrls.filterTo(linkedSetOf()) { url ->
            val committed = committedByUrl[url]
            val draft = draftByUrl[url]
            committed != null && (draft == null || (committed.enabled && !draft.enabled))
        },
    )
}
