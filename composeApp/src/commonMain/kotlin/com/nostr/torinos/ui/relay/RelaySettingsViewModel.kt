package com.nostr.torinos.ui.relay

import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

class RelaySettingsViewModel : SafeViewModel() {
    val entries: StateFlow<List<RelayEntry>> = RelayStore.entries

    private var publishRelayListJob: Job? = null

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
