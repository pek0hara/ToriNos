package com.nostr.torinos.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object NgWordStore {
    private const val KEY = "ng_words"
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _ngWords = MutableStateFlow<List<String>>(emptyList())
    val ngWords: StateFlow<List<String>> = _ngWords.asStateFlow()

    init {
        scope.launch { load() }
    }

    fun add(word: String) {
        val trimmed = word.trim()
        if (trimmed.isBlank() || _ngWords.value.any { it.equals(trimmed, ignoreCase = true) }) return
        _ngWords.value = _ngWords.value + trimmed
        save()
    }

    fun remove(word: String) {
        _ngWords.value = _ngWords.value.filter { it != word }
        save()
    }

    fun matches(content: String): Boolean {
        val words = _ngWords.value
        if (words.isEmpty()) return false
        return words.any { content.contains(it, ignoreCase = true) }
    }

    private suspend fun load() {
        val saved = LocalSettingsStorage.getString(KEY) ?: return
        runCatching { json.decodeFromString<List<String>>(saved) }.getOrNull()
            ?.let { _ngWords.value = it }
    }

    private fun save() {
        scope.launch {
            LocalSettingsStorage.putString(KEY, json.encodeToString(_ngWords.value))
        }
    }
}
