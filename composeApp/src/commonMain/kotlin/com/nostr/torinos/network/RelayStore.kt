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
    private const val SELECTED_FOLLOWING_RELAY_KEY = "selected_following_relay_url"
    private const val SELECTED_GLOBAL_RELAY_KEY = "selected_global_relay_url"
    private const val SELECTED_CHANNEL_RELAY_KEY = "selected_channel_relay_url"
    private const val SELECTED_STATUS_RELAY_KEY = "selected_status_relay_url"
    private const val SELECTED_MEMO_RELAY_KEY = "selected_memo_relay_url"
    private const val ALL_RELAYS_VALUE = "__all_relays__"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val defaults = listOf(
        RelayEntry("wss://yabu.me", enabled = true),
        RelayEntry("wss://relay-jp.nostr.wirednet.jp", enabled = true),
        RelayEntry("wss://r.kojira.io", enabled = true),
        RelayEntry("wss://relay.damus.io", enabled = true),
        RelayEntry("wss://nos.lol", enabled = true),
        RelayEntry("wss://relay.nostr.band", enabled = true),
        RelayEntry("wss://nostr.wine", enabled = true),
        RelayEntry("wss://search.nos.today", enabled = true),
        RelayEntry("wss://nostr.compile-error.net", enabled = true),
    )

    private val _entries = MutableStateFlow(defaults)
    private val _selectedFollowingRelayUrl = MutableStateFlow<String?>(null)
    private val _selectedGlobalRelayUrl = MutableStateFlow<String?>(null)
    private val _selectedChannelRelayUrl = MutableStateFlow<String?>(null)
    private val _selectedStatusRelayUrl = MutableStateFlow<String?>(null)
    private val _selectedMemoRelayUrl = MutableStateFlow<String?>(null)

    
    /** 全リレー一覧（UI 用） */
    val entries: StateFlow<List<RelayEntry>> = _entries.asStateFlow()

    /** 有効なリレーの URL 一覧（NostrRepository 用） */
    val relays = _entries.map { list -> list.filter { it.enabled }.map { it.url } }

    /** フォローフィードで選択中のリレー URL。null は「すべてのリレー」。 */
    val selectedFollowingRelayUrl: StateFlow<String?> = _selectedFollowingRelayUrl.asStateFlow()

    /** グローバルフィードで選択中のリレー URL */
    val selectedGlobalRelayUrl: StateFlow<String?> = _selectedGlobalRelayUrl.asStateFlow()

    /** チャンネル一覧・チャンネル画面で選択中のリレー URL */
    val selectedChannelRelayUrl: StateFlow<String?> = _selectedChannelRelayUrl.asStateFlow()

    /** ステータス画面で選択中のリレー URL */
    val selectedStatusRelayUrl: StateFlow<String?> = _selectedStatusRelayUrl.asStateFlow()

    /** ポストメモ画面で選択中のリレー URL */
    val selectedMemoRelayUrl: StateFlow<String?> = _selectedMemoRelayUrl.asStateFlow()

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

    fun setSelectedFollowingRelayUrl(url: String?) {
        setSelectedRelayUrl(
            state = _selectedFollowingRelayUrl,
            url = url,
            allowAll = true,
            save = ::saveSelectedFollowingRelay,
        )
    }

    fun setSelectedGlobalRelayUrl(url: String?) {
        setSelectedRelayUrl(
            state = _selectedGlobalRelayUrl,
            url = url,
            allowAll = false,
            save = ::saveSelectedGlobalRelay,
        )
    }

    fun setSelectedChannelRelayUrl(url: String?) {
        setSelectedRelayUrl(
            state = _selectedChannelRelayUrl,
            url = url,
            allowAll = false,
            save = ::saveSelectedChannelRelay,
        )
    }

    fun setSelectedStatusRelayUrl(url: String?) {
        setSelectedRelayUrl(
            state = _selectedStatusRelayUrl,
            url = url,
            allowAll = false,
            save = ::saveSelectedStatusRelay,
        )
    }

    fun setSelectedMemoRelayUrl(url: String?) {
        setSelectedRelayUrl(
            state = _selectedMemoRelayUrl,
            url = url,
            allowAll = false,
            save = ::saveSelectedMemoRelay,
        )
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

        val legacySelectedRelayUrl = LocalSettingsStorage.getString(SELECTED_RELAY_KEY)
            ?.takeIf { it in enabledRelayUrls() }

        val savedFollowingRelayUrl = LocalSettingsStorage.getString(SELECTED_FOLLOWING_RELAY_KEY)
        _selectedFollowingRelayUrl.value = when {
            savedFollowingRelayUrl == ALL_RELAYS_VALUE -> null
            savedFollowingRelayUrl in enabledRelayUrls() -> savedFollowingRelayUrl
            else -> legacySelectedRelayUrl
        }

        _selectedGlobalRelayUrl.value = LocalSettingsStorage.getString(SELECTED_GLOBAL_RELAY_KEY)
            ?.takeIf { it in enabledRelayUrls() }
            ?: legacySelectedRelayUrl
            ?: enabledRelayUrls().firstOrNull()

        _selectedChannelRelayUrl.value = LocalSettingsStorage.getString(SELECTED_CHANNEL_RELAY_KEY)
            ?.takeIf { it in enabledRelayUrls() }
            ?: legacySelectedRelayUrl
            ?: enabledRelayUrls().firstOrNull()

        _selectedStatusRelayUrl.value = LocalSettingsStorage.getString(SELECTED_STATUS_RELAY_KEY)
            ?.takeIf { it in enabledRelayUrls() }
            ?: legacySelectedRelayUrl
            ?: enabledRelayUrls().firstOrNull()

        _selectedMemoRelayUrl.value = LocalSettingsStorage.getString(SELECTED_MEMO_RELAY_KEY)
            ?.takeIf { it in enabledRelayUrls() }
            ?: legacySelectedRelayUrl
            ?: enabledRelayUrls().firstOrNull()
    }

    private fun ensureSelectedRelay() {
        val enabledUrls = enabledRelayUrls()
        if (_selectedFollowingRelayUrl.value != null && _selectedFollowingRelayUrl.value !in enabledUrls) {
            _selectedFollowingRelayUrl.value = null
            saveSelectedFollowingRelay()
        }
        if (_selectedGlobalRelayUrl.value !in enabledUrls) {
            _selectedGlobalRelayUrl.value = enabledUrls.firstOrNull()
            saveSelectedGlobalRelay()
        }
        if (_selectedChannelRelayUrl.value !in enabledUrls) {
            _selectedChannelRelayUrl.value = enabledUrls.firstOrNull()
            saveSelectedChannelRelay()
        }
        if (_selectedStatusRelayUrl.value !in enabledUrls) {
            _selectedStatusRelayUrl.value = enabledUrls.firstOrNull()
            saveSelectedStatusRelay()
        }
        if (_selectedMemoRelayUrl.value !in enabledUrls) {
            _selectedMemoRelayUrl.value = enabledUrls.firstOrNull()
            saveSelectedMemoRelay()
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

    private fun setSelectedRelayUrl(
        state: MutableStateFlow<String?>,
        url: String?,
        allowAll: Boolean,
        save: () -> Unit,
    ) {
        val normalized = url?.trim()?.takeIf { it.isNotBlank() }
        val enabledUrls = enabledRelayUrls()
        val selected = when {
            normalized == null && allowAll -> null
            normalized in enabledUrls -> normalized
            else -> enabledUrls.firstOrNull()
        }
        if (state.value == selected) return
        state.value = selected
        save()
    }

    private fun saveSelectedFollowingRelay() {
        val value = _selectedFollowingRelayUrl.value ?: ALL_RELAYS_VALUE
        scope.launch {
            LocalSettingsStorage.putString(SELECTED_FOLLOWING_RELAY_KEY, value)
        }
    }

    private fun saveSelectedGlobalRelay() {
        val value = _selectedGlobalRelayUrl.value
        scope.launch {
            LocalSettingsStorage.putString(SELECTED_GLOBAL_RELAY_KEY, value)
        }
    }

    private fun saveSelectedChannelRelay() {
        val value = _selectedChannelRelayUrl.value
        scope.launch {
            LocalSettingsStorage.putString(SELECTED_CHANNEL_RELAY_KEY, value)
        }
    }

    private fun saveSelectedStatusRelay() {
        val value = _selectedStatusRelayUrl.value
        scope.launch {
            LocalSettingsStorage.putString(SELECTED_STATUS_RELAY_KEY, value)
        }
    }

    private fun saveSelectedMemoRelay() {
        val value = _selectedMemoRelayUrl.value
        scope.launch {
            LocalSettingsStorage.putString(SELECTED_MEMO_RELAY_KEY, value)
        }
    }
}
