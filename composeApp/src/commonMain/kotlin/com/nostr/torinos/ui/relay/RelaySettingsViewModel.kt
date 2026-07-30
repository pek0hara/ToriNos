package com.nostr.torinos.ui.relay

import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.FollowedRelayDiscovery
import com.nostr.torinos.network.FollowedRelayDiscoveryResult
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayInformation
import com.nostr.torinos.network.RelayInformationRepository
import com.nostr.torinos.network.RelayStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class RelaySettingsViewModel : SafeViewModel() {
    val entries: StateFlow<List<RelayEntry>> = RelayStore.entries
    private val _informationState = MutableStateFlow(RelayInformationUiState())
    val informationState: StateFlow<RelayInformationUiState> = _informationState.asStateFlow()
    private val _discoveryState = MutableStateFlow(FollowedRelayDiscoveryUiState())
    val discoveryState: StateFlow<FollowedRelayDiscoveryUiState> = _discoveryState.asStateFlow()

    private var publishRelayListJob: Job? = null
    private var fetchInformationJob: Job? = null
    private var discoverFollowedRelaysJob: Job? = null

    init {
        discoverFollowedRelays()
    }

    fun add(url: String) {
        RelayStore.add(url)
        scheduleRelayListPublish()
    }

    fun remove(url: String) {
        RelayStore.remove(url)
        scheduleRelayListPublish()
    }

    fun setEnabled(url: String, enabled: Boolean) {
        RelayStore.setEnabled(url, enabled)
        scheduleRelayListPublish()
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

    private fun scheduleRelayListPublish() {
        if (!isWriteSupported) return
        publishRelayListJob?.cancel()
        publishRelayListJob = launch {
            delay(RELAY_LIST_PUBLISH_DEBOUNCE_MS)
            publishRelayList()
        }
    }

    private suspend fun publishRelayList() {
        val privateKeyHex = KeyStorage.loadPrivateKey() ?: return
        val enabledRelayUrls = entries.value
            .filter { it.enabled }
            .map { it.url.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val event = signEvent(
            privateKeyHex = privateKeyHex,
            content = "",
            kind = 10002,
            tags = enabledRelayUrls.map { listOf("r", it) },
        )
        NostrRepository.publish(event)
    }

    companion object {
        private const val RELAY_LIST_PUBLISH_DEBOUNCE_MS = 1_000L
    }
}
