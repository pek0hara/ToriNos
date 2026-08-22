package com.nostr.torinos.ui.status

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.AccountSessions
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.ui.profile.customEmojiMap
import com.nostr.torinos.ui.profile.customEmojiTagsForContent
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserStatus(
    val event: NostrEvent,
    val statusTag: String,
    val expiration: Long?,
    val referenceUrls: List<String>,
    val customEmojis: Map<String, String>,
) {
    val key: String = "${event.pubkey}:$statusTag"
}

private val DEFAULT_CATEGORIES = listOf("general", "music")

data class StatusState(
    val statuses: List<UserStatus> = emptyList(),
    val availableCategories: List<String> = DEFAULT_CATEGORIES,
    val selectedCategories: Set<String> = DEFAULT_CATEGORIES.toSet(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val isInitialLoad: Boolean = true,
    val isPublishing: Boolean = false,
    val publishCompletedCount: Int = 0,
    val errorMessage: String? = null,
)

class StatusViewModel(
    private val relayUrl: String? = null,
    private val accountSession: AccountSession? = AccountSessions.manager.currentSession,
) : SafeViewModel() {
    private val _state = MutableStateFlow(StatusState())
    val state: StateFlow<StatusState> = _state.asStateFlow()

    private val instanceKey = nextInstanceKey()
    private val relayKey = relayUrl?.hashCode()?.toString() ?: "all"
    private val statusSubId = "status-$relayKey-$instanceKey"
    private val rawStatuses = linkedMapOf<String, UserStatus>()
    private val pendingPubkeys = linkedSetOf<String>()
    private val jobs = mutableListOf<Job>()
    private var profileBatchJob: Job? = null
    private var started = false

    init {
        start()
    }

    fun toggleCategory(category: String) {
        val current = _state.value.selectedCategories
        val updated = if (category in current) current - category else current + category
        _state.value = _state.value.copy(selectedCategories = updated)
        rebuildStatuses()
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    fun publishStatus(statusTag: String, content: String, expiration: Long?, referenceUrl: String?) {
        val tag = statusTag.trim().ifBlank { "general" }
        val body = content.trim()
        val explicitReferenceUrl = referenceUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (body.isEmpty()) {
            _state.value = _state.value.copy(errorMessage = "ステータスを入力してください")
            return
        }
        launch {
            _state.value = _state.value.copy(isPublishing = true, errorMessage = null)
            try {
                val signer = accountSession?.signer ?: error("秘密鍵が見つかりません")
                val tags = buildList {
                    add(listOf("d", tag))
                    if (expiration != null) add(listOf("expiration", expiration.toString()))
                    addAll(customEmojiTagsForContent(body, CustomEmojiStore.emojis.value))
                    (listOfNotNull(explicitReferenceUrl) + extractWebUrls(body)).distinct().forEach { url ->
                        add(listOf("r", url))
                    }
                }
                val event = signer.sign(
                    content = body,
                    kind = STATUS_KIND,
                    tags = tags,
                )
                rememberStatus(event)
                if (relayUrl != null) {
                    NostrRepository.publishToRelays(event, listOf(relayUrl))
                } else {
                    NostrRepository.publish(event)
                }
                _state.value = _state.value.copy(
                    isPublishing = false,
                    publishCompletedCount = _state.value.publishCompletedCount + 1,
                )
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    isPublishing = false,
                    errorMessage = e.message ?: "ステータスの投稿に失敗しました",
                )
            }
        }
    }

    private fun start() {
        if (started) return
        started = true

        jobs += launch {
            NostrRepository.events(statusSubId).collect { event ->
                if (event.kind != STATUS_KIND) return@collect
                rememberStatus(event)
            }
        }
        jobs += launch {
            ProfileRepository.observeAll().collect { cachedProfiles ->
                val profiles = cachedProfiles.filterKeys { it in pendingPubkeys || it in _state.value.profiles }
                if (profiles != _state.value.profiles) _state.value = _state.value.copy(profiles = profiles)
            }
        }
        jobs += launch {
            NostrRepository.eose(statusSubId).collect {
                _state.value = _state.value.copy(isInitialLoad = false)
            }
        }
        jobs += launch {
            delay(INITIAL_LOAD_TIMEOUT_MS)
            if (_state.value.isInitialLoad) {
                _state.value = _state.value.copy(isInitialLoad = false)
            }
        }
        jobs += launch {
            while (started) {
                delay(EXPIRATION_REFRESH_INTERVAL_MS)
                rebuildStatuses()
            }
        }
        jobs += launch {
            MuteStore.mutedPubkeys.collect { rebuildStatuses() }
        }
        jobs += launch {
            NostrRepository.subscribe(
                statusSubId,
                NostrFilter(kinds = listOf(STATUS_KIND), limit = STATUS_LIMIT),
                relayUrl = relayUrl,
            )
        }
    }

    private fun rememberStatus(event: NostrEvent) {
        val statusTag = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: "general"
        val expiration = event.tags.firstOrNull { it.firstOrNull() == "expiration" }
            ?.getOrNull(1)
            ?.toLongOrNull()
        val referenceUrls = event.tags
            .filter { it.firstOrNull() == "r" }
            .mapNotNull { it.getOrNull(1)?.trim()?.takeIf { url -> url.isNotBlank() } }
            .distinct()
        val status = UserStatus(
            event = event,
            statusTag = statusTag,
            expiration = expiration,
            referenceUrls = referenceUrls,
            customEmojis = event.tags.customEmojiMap(),
        )
        val existing = rawStatuses[status.key]
        if (existing != null && existing.event.createdAt >= event.createdAt) return
        if (event.content.isBlank()) {
            rawStatuses.remove(status.key)
        } else {
            rawStatuses[status.key] = status
        }
        scheduleProfileFetch(event.pubkey)
        rebuildStatuses()
    }

    private fun rebuildStatuses() {
        val now = Clock.System.now().epochSeconds
        val selected = _state.value.selectedCategories
        val visibleCandidates = rawStatuses.values
            .filter { it.expiration == null || it.expiration > now }
            .filter { !MuteStore.isMuted(it.event.pubkey) }
        val extraCategories = visibleCandidates
            .map { it.statusTag }
            .distinct()
            .filter { it !in DEFAULT_CATEGORIES }
        val allCategories = DEFAULT_CATEGORIES + extraCategories
        val active = visibleCandidates
            .filter { selected.isEmpty() || it.statusTag in selected }
            .sortedByDescending { it.event.createdAt }
        _state.value = _state.value.copy(statuses = active, availableCategories = allCategories)
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in _state.value.profiles || !pendingPubkeys.add(pubkey)) return
        ProfileRepository.getCached(pubkey)?.let { profile ->
            _state.value = _state.value.copy(profiles = _state.value.profiles + (pubkey to profile))
        }
        profileBatchJob?.cancel()
        profileBatchJob = launch {
            delay(PROFILE_BATCH_DELAY_MS)
            ProfileRepository.ensureProfiles(
                pendingPubkeys.toSet(),
                ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS),
                relayHint = relayUrl,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        jobs.forEach { it.cancel() }
        profileBatchJob?.cancel()
        NostrRepository.close(statusSubId)
    }

    private companion object {
        const val STATUS_KIND = 30315
        const val STATUS_LIMIT = 200
        const val INITIAL_LOAD_TIMEOUT_MS = 5_000L
        const val EXPIRATION_REFRESH_INTERVAL_MS = 60_000L
        const val PROFILE_BATCH_DELAY_MS = 300L
        const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
        var nextKey = 0
        fun nextInstanceKey(): Int = nextKey++
    }
}

private val webUrlRegex = Regex("""https?://\S+""")

private fun extractWebUrls(content: String): List<String> =
    webUrlRegex.findAll(content)
        .mapNotNull { match ->
            match.value
                .trimEnd('.', ',', ';', ':', ')', ']', '}', '>', '"', '\'')
                .takeIf { it.isNotBlank() }
        }
        .distinct()
        .toList()
