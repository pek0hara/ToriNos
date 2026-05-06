package com.nostr.torinos.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class CustomEmoji(
    val shortcode: String,
    val imageUrl: String,
)

object CustomEmojiStore {
    private const val EMOJIS_KEY = "custom_emojis"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _emojis = MutableStateFlow<List<CustomEmoji>>(emptyList())
    private val _openSearchEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)

    val emojis: StateFlow<List<CustomEmoji>> = _emojis.asStateFlow()
    val openSearchEvent: SharedFlow<String> = _openSearchEvent.asSharedFlow()

    fun requestOpenSearch(shortcode: String) {
        _openSearchEvent.tryEmit(shortcode)
    }

    init {
        scope.launch {
            loadSavedState()
        }
    }

    fun add(shortcode: String, imageUrl: String) {
        val normalizedShortcode = shortcode.trim().trim(':')
        val normalizedUrl = imageUrl.trim()
        if (normalizedShortcode.isBlank() || normalizedUrl.isBlank()) return

        _emojis.update { current ->
            (current.filterNot { it.shortcode == normalizedShortcode } +
                CustomEmoji(normalizedShortcode, normalizedUrl))
                .sortedBy { it.shortcode.lowercase() }
        }
        save()
    }

    fun addAll(emojis: List<CustomEmoji>) {
        val normalizedEmojis = emojis.mapNotNull { emoji ->
            val normalizedShortcode = emoji.shortcode.trim().trim(':')
            val normalizedUrl = emoji.imageUrl.trim()
            if (normalizedShortcode.isBlank() || normalizedUrl.isBlank()) {
                null
            } else {
                CustomEmoji(normalizedShortcode, normalizedUrl)
            }
        }
        if (normalizedEmojis.isEmpty()) return

        _emojis.update { current ->
            (current.filterNot { saved -> normalizedEmojis.any { it.shortcode == saved.shortcode } } +
                normalizedEmojis)
                .distinctBy { it.shortcode }
                .sortedBy { it.shortcode.lowercase() }
        }
        save()
    }

    fun remove(shortcode: String) {
        _emojis.update { current -> current.filterNot { it.shortcode == shortcode } }
        save()
    }

    private suspend fun loadSavedState() {
        LocalSettingsStorage.getString(EMOJIS_KEY)
            ?.let { saved ->
                runCatching {
                    json.decodeFromString(ListSerializer(CustomEmoji.serializer()), saved)
                }.getOrNull()
            }
            ?.let { savedEmojis ->
                _emojis.value = savedEmojis
                    .filter { it.shortcode.isNotBlank() && it.imageUrl.isNotBlank() }
                    .distinctBy { it.shortcode }
                    .sortedBy { it.shortcode.lowercase() }
            }
    }

    private fun save() {
        val value = json.encodeToString(ListSerializer(CustomEmoji.serializer()), _emojis.value)
        scope.launch {
            LocalSettingsStorage.putString(EMOJIS_KEY, value)
        }
    }
}
