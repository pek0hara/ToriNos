package com.nostr.torinos.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

data class RelayEntry(val url: String, val enabled: Boolean)

object RelayStore {
    val defaults = listOf(
        RelayEntry("wss://yabu.me", enabled = true),
        RelayEntry("wss://relay.damus.io", enabled = false),
        RelayEntry("wss://nos.lol", enabled = false),
        RelayEntry("wss://relay.nostr.band", enabled = false),
        RelayEntry("wss://nostr.wine", enabled = false),
        RelayEntry("wss://search.nos.today", enabled = false),
        RelayEntry("wss://nostr.compile-error.net", enabled = false),
    )

    private val _entries = MutableStateFlow(defaults)

    /** 全リレー一覧（UI 用） */
    val entries: StateFlow<List<RelayEntry>> = _entries.asStateFlow()

    /** 有効なリレーの URL 一覧（NostrRepository 用） */
    val relays = _entries.map { list -> list.filter { it.enabled }.map { it.url } }

    fun add(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank() || _entries.value.any { it.url == trimmed }) return
        _entries.update { it + RelayEntry(trimmed, enabled = true) }
    }

    fun remove(url: String) {
        _entries.update { it.filter { e -> e.url != url } }
    }

    fun setEnabled(url: String, enabled: Boolean) {
        _entries.update { list -> list.map { if (it.url == url) it.copy(enabled = enabled) else it } }
    }
}
