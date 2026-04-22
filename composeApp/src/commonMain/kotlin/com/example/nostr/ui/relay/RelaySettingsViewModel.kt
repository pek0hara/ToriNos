package com.example.nostr.ui.relay

import androidx.lifecycle.ViewModel
import com.example.nostr.network.RelayEntry
import com.example.nostr.network.RelayStore
import kotlinx.coroutines.flow.StateFlow

class RelaySettingsViewModel : ViewModel() {
    val entries: StateFlow<List<RelayEntry>> = RelayStore.entries

    fun add(url: String) = RelayStore.add(url)
    fun remove(url: String) = RelayStore.remove(url)
    fun setEnabled(url: String, enabled: Boolean) = RelayStore.setEnabled(url, enabled)
}
