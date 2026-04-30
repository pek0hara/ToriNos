package com.nostr.torinos.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class RelayEntry(val url: String, val enabled: Boolean)

object RelayStore {
    private const val ENTRIES_KEY = "relay_entries"
    private const val SELECTED_RELAY_KEY = "selected_relay_url"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val defaults = listOf(
        RelayEntry("wss://yabu.me", enabled = true),
        RelayEntry("wss://relay-jp.nostr.wirednet.jp", enabled = true),
        RelayEntry("wss://r.kojira.io", enabled = true),
        RelayEntry("wss://relay.damus.io", enabled = false),
        RelayEntry("wss://nos.lol", enabled = false),
        RelayEntry("wss://relay.nostr.band", enabled = false),
        RelayEntry("wss://nostr.wine", enabled = false),
        RelayEntry("wss://search.nos.today", enabled = false),
        RelayEntry("wss://nostr.compile-error.net", enabled = false),
    )

    private val _entries = MutableStateFlow(defaults)
    private val _selectedRelayUrl = MutableStateFlow<String?>(null)

    /** 全リレー一覧（UI 用） */
    val entries: StateFlow<List<RelayEntry>> = _entries.asStateFlow()

    /** 有効なリレーの URL 一覧（NostrRepository 用） */
    val relays = _entries.map { list -> list.filter { it.enabled }.map { it.url } }

    /** フィードヘッダーで選択中のリレー URL */
    val selectedRelayUrl: StateFlow<String?> = _selectedRelayUrl.asStateFlow()

    init {
        scope.launch {
            loadSavedState()
        }
    }

    fun add(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank() || _entries.value.any { it.url == trimmed }) return
        _entries.update { it + RelayEntry(trimmed, enabled = true) }
        ensureSelectedRelay()
        saveEntries()
    }

    fun remove(url: String) {
        _entries.update { it.filter { e -> e.url != url } }
        ensureSelectedRelay()
        saveEntries()
    }

    fun setEnabled(url: String, enabled: Boolean) {
        _entries.update { list -> list.map { if (it.url == url) it.copy(enabled = enabled) else it } }
        ensureSelectedRelay()
        saveEntries()
    }

    fun setSelectedRelayUrl(url: String?) {
        val normalized = url?.trim()?.takeIf { it.isNotBlank() }
        val enabledUrls = enabledRelayUrls()
        val selected = normalized?.takeIf { it in enabledUrls } ?: enabledUrls.firstOrNull()
        if (_selectedRelayUrl.value == selected) return
        _selectedRelayUrl.value = selected
        saveSelectedRelay()
    }

    private suspend fun loadSavedState() {
        LocalSettingsStorage.getString(ENTRIES_KEY)
            ?.let { saved ->
                runCatching {
                    json.decodeFromString(ListSerializer(RelayEntry.serializer()), saved)
                }.getOrNull()
            }
            ?.takeIf { it.isNotEmpty() }
            ?.let { _entries.value = it }

        _selectedRelayUrl.value = LocalSettingsStorage.getString(SELECTED_RELAY_KEY)
            ?.takeIf { it in enabledRelayUrls() }
            ?: enabledRelayUrls().firstOrNull()
    }

    private fun ensureSelectedRelay() {
        val enabledUrls = enabledRelayUrls()
        val current = _selectedRelayUrl.value
        if (current !in enabledUrls) {
            _selectedRelayUrl.value = enabledUrls.firstOrNull()
            saveSelectedRelay()
        }
    }

    private fun enabledRelayUrls(): List<String> =
        _entries.value.filter { it.enabled }.map { it.url }

    private fun saveEntries() {
        val value = json.encodeToString(ListSerializer(RelayEntry.serializer()), _entries.value)
        scope.launch {
            LocalSettingsStorage.putString(ENTRIES_KEY, value)
        }
    }

    private fun saveSelectedRelay() {
        val value = _selectedRelayUrl.value
        scope.launch {
            LocalSettingsStorage.putString(SELECTED_RELAY_KEY, value)
        }
    }
}
