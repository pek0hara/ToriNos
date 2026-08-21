package com.nostr.torinos.ui.settings

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.CustomEmoji
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PublishedEmojiSet(
    val id: String,
    val sourceEventId: String,
    val name: String,
    val authorPubkey: String,
    val createdAt: Long,
    val emojis: List<CustomEmoji>,
)

data class CustomEmojiSettingsState(
    val publishedSets: List<PublishedEmojiSet> = emptyList(),
    val isLoadingPublishedSets: Boolean = false,
)

class CustomEmojiSettingsViewModel : SafeViewModel() {
    private val _state = MutableStateFlow(CustomEmojiSettingsState())
    val state: StateFlow<CustomEmojiSettingsState> = _state.asStateFlow()

    private var generation = 0
    private var eventJob: Job? = null
    private var eoseJob: Job? = null
    private var timeoutJob: Job? = null
    private val latestSets = mutableMapOf<String, PublishedEmojiSet>()

    init {
        refreshPublishedSets()
    }

    fun refreshPublishedSets() {
        val subId = nextSubId()
        latestSets.clear()
        _state.value = CustomEmojiSettingsState(isLoadingPublishedSets = true)

        eventJob?.cancel()
        eoseJob?.cancel()
        timeoutJob?.cancel()

        eventJob = launch {
            NostrRepository.events(subId).collect { event ->
                if (event.kind != KIND_EMOJI_SET) return@collect
                val set = event.toPublishedEmojiSet() ?: return@collect
                val current = latestSets[set.id]
                if (current == null || set.isPreferredTo(current)) {
                    latestSets[set.id] = set
                    publishState(isLoading = true)
                }
            }
        }

        eoseJob = launch {
            NostrRepository.eose(subId).collect {
                publishState(isLoading = false)
            }
        }

        timeoutJob = launch {
            delay(10_000)
            publishState(isLoading = false)
        }

        launch {
            NostrRepository.close(subId)
            NostrRepository.subscribe(
                subId,
                NostrFilter(kinds = listOf(KIND_EMOJI_SET), limit = 100),
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        eventJob?.cancel()
        eoseJob?.cancel()
        timeoutJob?.cancel()
        NostrRepository.close(currentSubId())
    }

    private fun publishState(isLoading: Boolean) {
        _state.update {
            it.copy(
                publishedSets = deduplicatePublishedEmojiSets(latestSets.values),
                isLoadingPublishedSets = isLoading,
            )
        }
    }

    private fun nextSubId(): String {
        generation += 1
        return currentSubId()
    }

    private fun currentSubId(): String = "custom-emojis-$generation"

    private fun NostrEvent.toPublishedEmojiSet(): PublishedEmojiSet? {
        val identifier = tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val title = tags.firstOrNull { it.firstOrNull() == "title" }?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: identifier
        val emojis = tags.mapNotNull { tag ->
            if (tag.firstOrNull() != "emoji") return@mapNotNull null
            val shortcode = tag.getOrNull(1)?.trim()?.trim(':')?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val imageUrl = tag.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            CustomEmoji(shortcode, imageUrl)
        }.distinctBy { it.shortcode }

        if (emojis.isEmpty()) return null

        return PublishedEmojiSet(
            id = "$pubkey:$identifier",
            sourceEventId = id,
            name = title,
            authorPubkey = pubkey,
            createdAt = createdAt,
            emojis = emojis,
        )
    }

    private companion object {
        const val KIND_EMOJI_SET = 30030
    }
}

/**
 * Relays may return stale versions of an addressable event and some publishers recreate the
 * same named set under a new `d` tag. Keep one deterministic, newest entry for both cases.
 */
internal fun deduplicatePublishedEmojiSets(
    sets: Collection<PublishedEmojiSet>,
): List<PublishedEmojiSet> = sets
    .sortedWith(
        compareByDescending<PublishedEmojiSet> { it.createdAt }
            .thenBy { it.sourceEventId },
    )
    .distinctBy { it.id }
    .distinctBy { set ->
        "${set.authorPubkey}:${set.name.trim().lowercase()}"
    }
    .sortedWith(
        compareByDescending<PublishedEmojiSet> { it.createdAt }
            .thenBy { it.name.lowercase() }
            .thenBy { it.sourceEventId },
    )

internal fun PublishedEmojiSet.isPreferredTo(other: PublishedEmojiSet): Boolean =
    createdAt > other.createdAt ||
        (createdAt == other.createdAt && sourceEventId < other.sourceEventId)
