package com.nostr.torinos.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object MuteStore {
    private const val KEY = "muted_pubkeys"
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _mutedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val mutedPubkeys: StateFlow<Set<String>> = _mutedPubkeys.asStateFlow()

    init {
        scope.launch { load() }
    }

    fun mute(pubkey: String) {
        _mutedPubkeys.update { it + pubkey }
        save()
    }

    fun unmute(pubkey: String) {
        _mutedPubkeys.update { it - pubkey }
        save()
    }

    fun isMuted(pubkey: String): Boolean = _mutedPubkeys.value.contains(pubkey)

    private suspend fun load() {
        val saved = LocalSettingsStorage.getString(KEY) ?: return
        runCatching { json.decodeFromString<List<String>>(saved) }.getOrNull()
            ?.let { _mutedPubkeys.value = it.toSet() }
    }

    private fun save() {
        scope.launch {
            LocalSettingsStorage.putString(KEY, json.encodeToString(_mutedPubkeys.value.toList()))
        }
    }
}
