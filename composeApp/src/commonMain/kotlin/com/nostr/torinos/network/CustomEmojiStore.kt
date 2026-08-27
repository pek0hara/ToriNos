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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
data class CustomEmoji(
    val shortcode: String,
    val imageUrl: String,
)

@Serializable
data class CustomEmojiList(
    val id: String,
    val name: String,
    val emojis: List<CustomEmoji>,
    val authorPubkey: String = "",
)

@Serializable
data class RecentReaction(
    val kind: String,
    val value: String,
) {
    companion object {
        const val UnicodeKind = "unicode"
        const val CustomKind = "custom"
    }
}

data class CustomEmojiOpenRequest(
    val shortcode: String,
    val imageUrl: String = "",
)

object CustomEmojiStore {
    private const val EMOJIS_KEY = "custom_emojis"
    private const val EMOJI_LISTS_KEY = "custom_emoji_lists"
    private const val RECENT_EMOJIS_KEY = "recent_custom_emojis"
    private const val RECENT_REACTIONS_KEY = "recent_reactions"
    private const val MAX_RECENT_EMOJIS = 24
    private const val MANUAL_LIST_ID = "manual"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _emojis = MutableStateFlow<List<CustomEmoji>>(emptyList())
    private val _emojiLists = MutableStateFlow<List<CustomEmojiList>>(emptyList())
    private val _recentEmojiShortcodes = MutableStateFlow<List<String>>(emptyList())
    private val _recentReactions = MutableStateFlow<List<RecentReaction>>(emptyList())
    private val _openSearchEvent = MutableSharedFlow<CustomEmojiOpenRequest>(extraBufferCapacity = 1)

    val emojis: StateFlow<List<CustomEmoji>> = _emojis.asStateFlow()
    val emojiLists: StateFlow<List<CustomEmojiList>> = _emojiLists.asStateFlow()
    val recentEmojiShortcodes: StateFlow<List<String>> = _recentEmojiShortcodes.asStateFlow()
    val recentReactions: StateFlow<List<RecentReaction>> = _recentReactions.asStateFlow()
    val openSearchEvent: SharedFlow<CustomEmojiOpenRequest> = _openSearchEvent.asSharedFlow()

    fun requestOpenSearch(shortcode: String, imageUrl: String = "") {
        _openSearchEvent.tryEmit(
            CustomEmojiOpenRequest(
                shortcode = shortcode.trim().trim(':'),
                imageUrl = imageUrl.trim(),
            ),
        )
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
        upsertEmojiList(
            CustomEmojiList(
                id = MANUAL_LIST_ID,
                name = "カスタム絵文字",
                emojis = listOf(CustomEmoji(normalizedShortcode, normalizedUrl)),
            ),
            merge = true,
        )
        save()
    }

    fun markUsed(shortcode: String) {
        val normalizedShortcode = shortcode.trim().trim(':')
        if (normalizedShortcode.isBlank()) return
        _recentEmojiShortcodes.update { current ->
            (listOf(normalizedShortcode) + current.filterNot { it == normalizedShortcode })
                .take(MAX_RECENT_EMOJIS)
        }
        saveRecent()
    }

    fun markCustomReactionUsed(shortcode: String) {
        val normalizedShortcode = shortcode.trim().trim(':')
        if (normalizedShortcode.isBlank()) return
        _recentEmojiShortcodes.update { current ->
            (listOf(normalizedShortcode) + current.filterNot { it == normalizedShortcode })
                .take(MAX_RECENT_EMOJIS)
        }
        markReactionUsed(RecentReaction(RecentReaction.CustomKind, normalizedShortcode))
        saveRecent()
    }

    fun markUnicodeUsed(value: String) {
        val normalizedValue = value.trim()
        if (normalizedValue.isBlank()) return
        markReactionUsed(RecentReaction(RecentReaction.UnicodeKind, normalizedValue))
        saveRecent()
    }

    fun addAll(emojis: List<CustomEmoji>) {
        addList(
            id = MANUAL_LIST_ID,
            name = "カスタム絵文字",
            emojis = emojis,
            merge = true,
        )
    }

    fun addList(
        id: String,
        name: String,
        emojis: List<CustomEmoji>,
        authorPubkey: String = "",
        merge: Boolean = false,
    ) {
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
        val normalizedId = id.trim().takeIf { it.isNotBlank() } ?: name.trim().ifBlank { MANUAL_LIST_ID }
        val normalizedName = name.trim().ifBlank { "カスタム絵文字" }

        _emojis.update { current ->
            (current.filterNot { saved -> normalizedEmojis.any { it.shortcode == saved.shortcode } } +
                normalizedEmojis)
                .distinctBy { it.shortcode }
                .sortedBy { it.shortcode.lowercase() }
        }
        upsertEmojiList(
            CustomEmojiList(
                id = normalizedId,
                name = normalizedName,
                emojis = normalizedEmojis,
                authorPubkey = authorPubkey.trim(),
            ),
            merge = merge,
        )
        save()
    }

    fun remove(shortcode: String) {
        _emojis.update { current -> current.filterNot { it.shortcode == shortcode } }
        _emojiLists.update { lists ->
            lists.mapNotNull { list ->
                val updatedEmojis = list.emojis.filterNot { it.shortcode == shortcode }
                if (updatedEmojis.isEmpty()) null else list.copy(emojis = updatedEmojis)
            }
        }
        save()
    }

    fun removeList(id: String, fallbackEmojis: List<CustomEmoji> = emptyList()) {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) return

        val currentLists = _emojiLists.value
        val removedList = currentLists.firstOrNull { it.id == normalizedId }
        val removedEmojis = removedList?.emojis
            ?: fallbackEmojis.mapNotNull { emoji ->
                val normalizedShortcode = emoji.shortcode.trim().trim(':')
                val normalizedUrl = emoji.imageUrl.trim()
                if (normalizedShortcode.isBlank() || normalizedUrl.isBlank()) {
                    null
                } else {
                    CustomEmoji(normalizedShortcode, normalizedUrl)
                }
            }
        val updatedLists = currentLists
            .filterNot { it.id == normalizedId }
            .mapNotNull { list ->
                if (removedList != null) return@mapNotNull list
                val updatedEmojis = list.emojis.filterNot { saved ->
                    removedEmojis.any { removed ->
                        saved.shortcode == removed.shortcode && saved.imageUrl == removed.imageUrl
                    }
                }
                if (updatedEmojis.isEmpty()) null else list.copy(emojis = updatedEmojis)
            }
        _emojiLists.value = updatedLists
        val remainingEmojis = updatedLists
            .flatMap { it.emojis }
            .distinctBy { it.shortcode }
            .sortedBy { it.shortcode.lowercase() }
        _emojis.value = if (removedList == null) {
            _emojis.value.filterNot { saved ->
                removedEmojis.any { removed ->
                    saved.shortcode == removed.shortcode && saved.imageUrl == removed.imageUrl
                }
            }
        } else {
            remainingEmojis
        }.distinctBy { it.shortcode }
            .sortedBy { it.shortcode.lowercase() }
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
        val savedLists = LocalSettingsStorage.getString(EMOJI_LISTS_KEY)
            ?.let { saved ->
                runCatching {
                    json.decodeFromString(ListSerializer(CustomEmojiList.serializer()), saved)
                }.getOrNull()
            }
            .orEmpty()
            .mapNotNull { list ->
                val emojis = list.emojis
                    .filter { it.shortcode.isNotBlank() && it.imageUrl.isNotBlank() }
                    .distinctBy { it.shortcode }
                    .sortedBy { it.shortcode.lowercase() }
                if (emojis.isEmpty()) {
                    null
                } else {
                    list.copy(
                        id = list.id.ifBlank { list.name },
                        name = list.name.ifBlank { "カスタム絵文字" },
                        emojis = emojis,
                    )
                }
            }
        _emojiLists.value = savedLists.takeIf { it.isNotEmpty() }
            ?: _emojis.value.takeIf { it.isNotEmpty() }?.let { savedEmojis ->
                listOf(
                    CustomEmojiList(
                        id = MANUAL_LIST_ID,
                        name = "カスタム絵文字",
                        emojis = savedEmojis,
                    ),
                )
            }
            ?: emptyList()
        LocalSettingsStorage.getString(RECENT_EMOJIS_KEY)
            ?.let { saved ->
                runCatching {
                    json.decodeFromString(ListSerializer(String.serializer()), saved)
                }.getOrNull()
            }
            ?.let { savedShortcodes ->
                _recentEmojiShortcodes.value = savedShortcodes
                    .map { it.trim().trim(':') }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(MAX_RECENT_EMOJIS)
            }
        val savedRecentReactions = LocalSettingsStorage.getString(RECENT_REACTIONS_KEY)
            ?.let { saved ->
                runCatching {
                    json.decodeFromString(ListSerializer(RecentReaction.serializer()), saved)
                }.getOrNull()
            }
            .orEmpty()
            .filter {
                it.value.isNotBlank() &&
                    (it.kind == RecentReaction.UnicodeKind || it.kind == RecentReaction.CustomKind)
            }
            .distinctBy { it.kind to it.value }
            .take(MAX_RECENT_EMOJIS)
        _recentReactions.value = savedRecentReactions.ifEmpty {
            _recentEmojiShortcodes.value.map {
                RecentReaction(RecentReaction.CustomKind, it)
            }
        }
    }

    private fun save() {
        val value = json.encodeToString(ListSerializer(CustomEmoji.serializer()), _emojis.value)
        val listsValue = json.encodeToString(ListSerializer(CustomEmojiList.serializer()), _emojiLists.value)
        scope.launch {
            LocalSettingsStorage.putString(EMOJIS_KEY, value)
            LocalSettingsStorage.putString(EMOJI_LISTS_KEY, listsValue)
        }
    }

    private fun saveRecent() {
        val value = json.encodeToString(ListSerializer(String.serializer()), _recentEmojiShortcodes.value)
        val reactionsValue = json.encodeToString(
            ListSerializer(RecentReaction.serializer()),
            _recentReactions.value,
        )
        scope.launch {
            LocalSettingsStorage.putString(RECENT_EMOJIS_KEY, value)
            LocalSettingsStorage.putString(RECENT_REACTIONS_KEY, reactionsValue)
        }
    }

    private fun markReactionUsed(reaction: RecentReaction) {
        _recentReactions.update { current ->
            (listOf(reaction) + current.filterNot {
                it.kind == reaction.kind && it.value == reaction.value
            }).take(MAX_RECENT_EMOJIS)
        }
    }

    private fun upsertEmojiList(list: CustomEmojiList, merge: Boolean) {
        _emojiLists.update { current ->
            val existing = current.firstOrNull { it.id == list.id }
            val updated = if (merge && existing != null) {
                list.copy(
                    emojis = (existing.emojis.filterNot { saved ->
                        list.emojis.any { it.shortcode == saved.shortcode }
                    } + list.emojis)
                        .distinctBy { it.shortcode }
                        .sortedBy { it.shortcode.lowercase() },
                )
            } else {
                list.copy(emojis = list.emojis.sortedBy { it.shortcode.lowercase() })
            }
            (current.filterNot { it.id == list.id } + updated)
                .sortedBy { it.name.lowercase() }
        }
    }
}
