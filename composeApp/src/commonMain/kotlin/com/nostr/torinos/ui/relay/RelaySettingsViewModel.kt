package com.nostr.torinos.ui.relay

import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayStore
import kotlinx.coroutines.flow.StateFlow

class RelaySettingsViewModel : SafeViewModel() {
    val entries: StateFlow<List<RelayEntry>> = RelayStore.entries

    fun add(url: String) = RelayStore.add(url)
    fun remove(url: String) = RelayStore.remove(url)
    fun setEnabled(url: String, enabled: Boolean) = RelayStore.setEnabled(url, enabled)
}
