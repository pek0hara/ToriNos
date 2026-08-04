package com.nostr.torinos.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nostr.torinos.ui.SafeViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.incrementedWithUnicodeReaction
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toUnicodeReaction
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.util.networkTraceLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel : SafeViewModel() {

    sealed interface UiState {
        data object Idle : UiState
        data object Loading : UiState
        data class Ready(
            val query: String,
            val events: List<NostrEvent> = emptyList(),
            val profiles: Map<String, NostrProfile> = emptyMap(),
            val reactionCounts: Map<String, Int> = emptyMap(),
            val likeReactionCounts: Map<String, Int> = emptyMap(),
            val customReactions: Map<String, List<CustomReaction>> = emptyMap(),
            val unicodeReactions: Map<String, List<UnicodeReaction>> = emptyMap(),
            val replyCounts: Map<String, Int> = emptyMap(),
            val repostCounts: Map<String, Int> = emptyMap(),
            val canLoadMore: Boolean = false,
            val users: List<Pair<String, NostrProfile>> = emptyList(),
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var currentQuery = ""
    private val seenEventIds = linkedSetOf<String>()
    private val pendingPubkeys = mutableSetOf<String>()
    private var profileBatchJob: Job? = null
    private var oldestCreatedAt: Long? = null
    private var loadingMore = false
    private var lastBatchCount = 0
    private var receivedEoseCount = 0
    private var expectedEoseCount = 1
    private val subscriptionJobs = mutableListOf<Job>()
    private var pageTimeoutJob: Job? = null
    private var currentEvents = emptyList<NostrEvent>()
    private var currentProfiles = emptyMap<String, NostrProfile>()
    private var currentReactionCounts = emptyMap<String, Int>()
    private var currentLikeReactionCounts = emptyMap<String, Int>()
    private var currentCustomReactions = emptyMap<String, List<CustomReaction>>()
    private var currentUnicodeReactions = emptyMap<String, List<UnicodeReaction>>()
    private var currentReplyCounts = emptyMap<String, Int>()
    private var currentRepostCounts = emptyMap<String, Int>()
    private val watchedEventIds = linkedSetOf<String>()
    private var engagementBatchJob: Job? = null
    private val seenReactionIds = linkedSetOf<String>()
    private val seenReplyIds = linkedSetOf<String>()
    private val seenRepostIds = linkedSetOf<String>()
    private val seenQuoteRepostIds = linkedSetOf<String>()

    private var searchGeneration = 0
    private var activeSearchSubId = "srch-main-0"
    private val profileSubId = "sprof-main"
    private var activeReactionSubId = "sreact-main-0"
    private var activeReplySubId = "sreply-main-0"
    private var activeRepostSubId = "srepost-main-0"
    private var activeQuoteRepostSubId = "sqrepost-main-0"
    private var activeUserSubId = "suser-main-0"
    private val seenUserIds = linkedSetOf<String>()
    private var currentUsers = emptyList<Pair<String, NostrProfile>>()

    fun search(query: String) {
        val trimmed = query.trim()
        networkTraceLog { "[Search] search() called: query='$trimmed'" }
        if (trimmed.isBlank()) {
            networkTraceLog { "[Search] query is blank, ignoring" }
            return
        }

        stopSubscriptions()
        val generation = ++searchGeneration
        activeSearchSubId = "srch-main-$generation"
        activeReactionSubId = "sreact-main-$generation"
        activeReplySubId = "sreply-main-$generation"
        activeRepostSubId = "srepost-main-$generation"
        activeQuoteRepostSubId = "sqrepost-main-$generation"
        activeUserSubId = "suser-main-$generation"

        currentQuery = trimmed
        seenEventIds.clear()
        pendingPubkeys.clear()
        oldestCreatedAt = null
        loadingMore = false
        lastBatchCount = 0
        receivedEoseCount = 0
        currentEvents = emptyList()
        currentProfiles = emptyMap()
        currentReactionCounts = emptyMap()
        currentLikeReactionCounts = emptyMap()
        currentCustomReactions = emptyMap()
        currentUnicodeReactions = emptyMap()
        currentReplyCounts = emptyMap()
        currentRepostCounts = emptyMap()
        watchedEventIds.clear()
        seenReactionIds.clear()
        seenReplyIds.clear()
        seenRepostIds.clear()
        seenQuoteRepostIds.clear()
        seenUserIds.clear()
        currentUsers = emptyList()
        _state.value = UiState.Loading

        startSubscriptions()
    }

    fun loadMore() {
        if (loadingMore || (_state.value as? UiState.Ready)?.canLoadMore != true) return
        launch {
            requestPage(until = oldestCreatedAt?.minus(1))
        }
    }

    private fun startSubscriptions() {
        networkTraceLog { "[Search] startSubscriptions() relayCount=${NostrRepository.relayCount}" }

        // 検索結果イベントを収集
        val searchSubId = activeSearchSubId
        val reactionSubId = activeReactionSubId
        val replySubId = activeReplySubId
        val repostSubId = activeRepostSubId
        val quoteRepostSubId = activeQuoteRepostSubId
        val userSubId = activeUserSubId
        subscriptionJobs += launch {
            NostrRepository.events(searchSubId).collect { event ->
                if (event.kind != 1) return@collect
                val added = appendEvent(event)
                lastBatchCount += added
                networkTraceLog {
                    "[Search] event received id=${event.id.take(8)} pubkey=${event.pubkey.take(8)} " +
                        "added=$added totalSeen=${seenEventIds.size}"
                }
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
                scheduleEngagementFetch(event.id)
            }
        }

        // EOSE でページ完了
        subscriptionJobs += launch {
            NostrRepository.eose(searchSubId).collect {
                receivedEoseCount++
                networkTraceLog {
                    "[Search] EOSE received receivedEoseCount=$receivedEoseCount " +
                        "expectedEoseCount=$expectedEoseCount lastBatchCount=$lastBatchCount"
                }
                if (receivedEoseCount >= expectedEoseCount) {
                    onPageCompleted()
                }
            }
        }

        // 10秒タイムアウトフォールバック
        subscriptionJobs += launch {
            delay(10_000)
            if (_state.value is UiState.Loading) {
                networkTraceLog { "[Search] timeout: no EOSE received in 10s, forcing Ready state" }
                _state.value = readyState(canLoadMore = false)
            }
        }

        // プロフィール受信（kind:0）
        subscriptionJobs += launch {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                networkTraceLog { "[Search] profile received pubkey=${event.pubkey.take(8)} name=${profile.name}" }
                pendingPubkeys.remove(event.pubkey)
                currentProfiles = currentProfiles + (event.pubkey to profile)
                syncReadyState()
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(reactionSubId).collect { event ->
                if (event.kind != 7) return@collect
                if (!rememberSeenId(seenReactionIds, event.id)) return@collect
                val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                    ?: return@collect
                currentReactionCounts = currentReactionCounts +
                    (targetId to (currentReactionCounts[targetId] ?: 0) + 1)
                if (event.content.trim() == "+") {
                    currentLikeReactionCounts = currentLikeReactionCounts + (
                        targetId to (currentLikeReactionCounts[targetId] ?: 0) + 1
                        )
                }
                event.toCustomReaction()?.let { reaction ->
                    currentCustomReactions = currentCustomReactions + (
                        targetId to currentCustomReactions[targetId]
                            .orEmpty()
                            .incrementedWith(reaction)
                    )
                }
                event.toUnicodeReaction()?.let { reaction ->
                    currentUnicodeReactions = currentUnicodeReactions + (
                        targetId to currentUnicodeReactions[targetId]
                            .orEmpty()
                            .incrementedWithUnicodeReaction(reaction)
                    )
                }
                syncReadyState()
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(replySubId).collect { event ->
                if (event.kind != 1) return@collect
                if (!rememberSeenId(seenReplyIds, event.id)) return@collect
                val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                    ?: return@collect
                currentReplyCounts = currentReplyCounts +
                    (targetId to (currentReplyCounts[targetId] ?: 0) + 1)
                syncReadyState()
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(repostSubId).collect { event ->
                if (event.kind != 6) return@collect
                if (!rememberSeenId(seenRepostIds, event.id)) return@collect
                val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                    ?: return@collect
                currentRepostCounts = currentRepostCounts +
                    (targetId to (currentRepostCounts[targetId] ?: 0) + 1)
                syncReadyState()
            }
        }

        subscriptionJobs += launch {
            NostrRepository.events(quoteRepostSubId).collect { event ->
                if (event.kind != 1) return@collect
                val targetIds = event.tags
                    .filter { it.firstOrNull() == "q" }
                    .mapNotNull { it.getOrNull(1) }
                    .distinct()
                    .filter { it in watchedEventIds }
                    .filter { rememberSeenId(seenQuoteRepostIds, "${event.id}:$it") }
                if (targetIds.isEmpty()) return@collect
                val counts = currentRepostCounts.toMutableMap()
                targetIds.forEach { targetId -> counts[targetId] = (counts[targetId] ?: 0) + 1 }
                currentRepostCounts = counts
                syncReadyState()
            }
        }

        // ユーザー検索結果（kind:0）
        subscriptionJobs += launch {
            NostrRepository.events(userSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                if (!seenUserIds.add(event.pubkey)) return@collect
                currentUsers = currentUsers + (event.pubkey to profile)
                syncReadyState()
            }
        }

        launch {
            requestPage(until = null)
        }
    }

    private fun stopSubscriptions() {
        subscriptionJobs.forEach { it.cancel() }
        subscriptionJobs.clear()
        profileBatchJob?.cancel()
        engagementBatchJob?.cancel()
        pageTimeoutJob?.cancel()
        pageTimeoutJob = null
        loadingMore = false
        NostrRepository.closeTemporaryRelay(activeSearchSubId)
        NostrRepository.closeTemporaryRelay(activeUserSubId)
        NostrRepository.close(profileSubId)
        NostrRepository.close(activeReactionSubId)
        NostrRepository.close(activeReplySubId)
        NostrRepository.close(activeRepostSubId)
        NostrRepository.close(activeQuoteRepostSubId)
    }

    override fun onCleared() {
        super.onCleared()
        stopSubscriptions()
    }

    private suspend fun requestPage(until: Long?) {
        loadingMore = true
        lastBatchCount = 0
        receivedEoseCount = 0
        expectedEoseCount = 1
        val current = _state.value as? UiState.Ready
        if (current != null) {
            _state.value = current.copy(canLoadMore = false)
        }

        val filter = buildFilter(until)
        val searchSubId = activeSearchSubId
        schedulePageTimeout(searchSubId)
        networkTraceLog { "[Search] requestPage() until=$until expectedEoseCount=$expectedEoseCount filter=$filter" }
        NostrRepository.subscribeTemporaryRelay(searchSubId, filter, SEARCH_RELAY_URL)

        if (until == null) {
            val q = currentQuery
            val userSubId = activeUserSubId
            NostrRepository.subscribeTemporaryRelay(
                userSubId,
                NostrFilter(kinds = listOf(0), search = q, limit = USER_SEARCH_LIMIT),
                SEARCH_RELAY_URL,
            )
        }
    }

    private fun buildFilter(until: Long?): NostrFilter {
        val q = currentQuery
        networkTraceLog { "[Search] buildFilter: NIP-50 search='$q' until=$until" }
        return NostrFilter(kinds = listOf(1), search = q, until = until, limit = PAGE_SIZE)
    }

    private fun onPageCompleted() {
        if (!loadingMore) return
        loadingMore = false
        pageTimeoutJob?.cancel()
        pageTimeoutJob = null
        val hasMore = lastBatchCount >= PAGE_SIZE
        networkTraceLog {
            "[Search] onPageCompleted() lastBatchCount=$lastBatchCount hasMore=$hasMore totalEvents=${currentEvents.size}"
        }
        _state.value = readyState(canLoadMore = hasMore)
        if (!hasMore) NostrRepository.closeTemporaryRelay(activeSearchSubId)
    }

    private fun schedulePageTimeout(searchSubId: String) {
        pageTimeoutJob?.cancel()
        pageTimeoutJob = launch {
            delay(10_000)
            if (!loadingMore || searchSubId != activeSearchSubId) return@launch

            loadingMore = false
            val hasMore = lastBatchCount >= PAGE_SIZE
            networkTraceLog { "[Search] page timeout: forcing Ready state hasMore=$hasMore" }
            _state.value = readyState(canLoadMore = hasMore)
            if (!hasMore) NostrRepository.closeTemporaryRelay(searchSubId)
        }
    }

    private fun appendEvent(event: NostrEvent): Int {
        if (!seenEventIds.add(event.id)) return 0
        currentEvents = (currentEvents + event).sortedByDescending { it.createdAt }
        oldestCreatedAt = currentEvents.lastOrNull()?.createdAt
        syncReadyState()
        return 1
    }

    private fun readyState(canLoadMore: Boolean): UiState.Ready =
        UiState.Ready(
            query = currentQuery,
            events = currentEvents,
            profiles = currentProfiles,
            reactionCounts = currentReactionCounts,
            likeReactionCounts = currentLikeReactionCounts,
            customReactions = currentCustomReactions,
            unicodeReactions = currentUnicodeReactions,
            replyCounts = currentReplyCounts,
            repostCounts = currentRepostCounts,
            canLoadMore = canLoadMore,
            users = currentUsers,
        )

    private fun syncReadyState() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(
            query = currentQuery,
            events = currentEvents,
            profiles = currentProfiles,
            reactionCounts = currentReactionCounts,
            likeReactionCounts = currentLikeReactionCounts,
            customReactions = currentCustomReactions,
            unicodeReactions = currentUnicodeReactions,
            replyCounts = currentReplyCounts,
            repostCounts = currentRepostCounts,
            users = currentUsers,
        )
    }

    private fun rememberSeenId(seenIds: LinkedHashSet<String>, eventId: String): Boolean {
        if (!seenIds.add(eventId)) return false
        while (seenIds.size > MAX_SEEN_IDS) seenIds.remove(seenIds.first())
        return true
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in currentProfiles || pubkey in pendingPubkeys) return
        pendingPubkeys.add(pubkey)
        profileBatchJob?.cancel()
        profileBatchJob = launch {
            delay(500)
            if (pendingPubkeys.isEmpty()) return@launch
            val authors = pendingPubkeys.toList()
            pendingPubkeys.removeAll(authors)
            NostrRepository.subscribe(
                profileSubId,
                NostrFilter(kinds = listOf(0), authors = authors),
            )
        }
    }

    private fun scheduleEngagementFetch(eventId: String) {
        if (!watchedEventIds.add(eventId)) return
        while (watchedEventIds.size > MAX_TRACKED_ENGAGEMENT_EVENTS) {
            watchedEventIds.remove(watchedEventIds.first())
        }
        engagementBatchJob?.cancel()
        engagementBatchJob = launch {
            delay(500)
            val ids = watchedEventIds.toList()
            NostrRepository.subscribe(activeReactionSubId, NostrFilter(kinds = listOf(7), eTags = ids))
            NostrRepository.subscribe(activeReplySubId, NostrFilter(kinds = listOf(1), eTags = ids))
            NostrRepository.subscribe(activeRepostSubId, NostrFilter(kinds = listOf(6), eTags = ids))
            NostrRepository.subscribe(activeQuoteRepostSubId, NostrFilter(kinds = listOf(1), qTags = ids))
        }
    }

    private fun scheduleMentionedProfileFetch(text: String) {
        extractNpubReferences(text).forEach { reference ->
            scheduleProfileFetch(reference.pubkey)
        }
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val USER_SEARCH_LIMIT = 20
        private const val MAX_SEEN_IDS = 2_000
        private const val MAX_TRACKED_ENGAGEMENT_EVENTS = 100
        private const val SEARCH_RELAY_URL = "wss://search.nos.today"

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                SearchViewModel() as T
        }
    }
}
