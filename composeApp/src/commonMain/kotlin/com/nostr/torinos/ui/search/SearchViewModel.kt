package com.nostr.torinos.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.incrementedWithUnicodeReaction
import com.nostr.torinos.model.isSupportedTimelineComment
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toUnicodeReaction
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.util.networkTraceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.reflect.KClass
import kotlin.time.Clock

enum class SearchTab {
    Posts,
    Users,
}

enum class SearchLoadState {
    NotRequested,
    Loading,
    Interrupted,
    Ready,
    Failed,
}

/** 途中で離れたページの境界と、再取得したページ自体の一意受信件数を保持する。 */
internal data class SearchPageBatch(val count: Int, val oldestCreatedAt: Long?)

internal class SearchPageProgress {
    var interruptedUntil: Long? = null
        private set
    private var activeUntil: Long? = null
    private var active = false
    private val receivedIds = mutableSetOf<String>()
    private var oldestCreatedAt: Long? = null

    fun start(until: Long?) {
        activeUntil = until
        active = true
        receivedIds.clear()
        oldestCreatedAt = null
    }

    fun observe(eventId: String, createdAt: Long) {
        if (active && receivedIds.add(eventId)) {
            oldestCreatedAt = minOf(oldestCreatedAt ?: createdAt, createdAt)
        }
    }

    fun interrupt() {
        if (active) interruptedUntil = activeUntil
        active = false
    }

    fun complete(): SearchPageBatch {
        active = false
        interruptedUntil = null
        return SearchPageBatch(receivedIds.size, oldestCreatedAt)
    }

    fun reset() {
        active = false
        activeUntil = null
        interruptedUntil = null
        receivedIds.clear()
        oldestCreatedAt = null
    }
}

class SearchViewModel : SafeViewModel() {
    data class UiState(
        val query: String = "",
        val selectedTab: SearchTab = SearchTab.Posts,
        val postsLoadState: SearchLoadState = SearchLoadState.NotRequested,
        val usersLoadState: SearchLoadState = SearchLoadState.NotRequested,
        val events: List<NostrEvent> = emptyList(),
        val profiles: Map<String, NostrProfile> = emptyMap(),
        val reactionCounts: Map<String, Int> = emptyMap(),
        val likeReactionCounts: Map<String, Int> = emptyMap(),
        val customReactions: Map<String, List<CustomReaction>> = emptyMap(),
        val unicodeReactions: Map<String, List<UnicodeReaction>> = emptyMap(),
        val reactionEvents: Map<String, List<NostrEvent>> = emptyMap(),
        val replyCounts: Map<String, Int> = emptyMap(),
        val repostCounts: Map<String, Int> = emptyMap(),
        val repostPubkeys: Map<String, List<String>> = emptyMap(),
        val canLoadMore: Boolean = false,
        val users: List<Pair<String, NostrProfile>> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var currentQuery = ""
    private var searchGeneration = 0
    private var pageSerial = 0
    private var engagementSerial = 0

    private val seenEventIds = linkedSetOf<String>()
    private val seenUserIds = linkedSetOf<String>()
    private val pendingPubkeys = mutableSetOf<String>()
    private val watchedEventIds = linkedSetOf<String>()
    private val seenReactionIds = linkedSetOf<String>()
    private val seenReplyIds = linkedSetOf<String>()
    private val seenRepostIds = linkedSetOf<String>()
    private val seenQuoteRepostIds = linkedSetOf<String>()

    private var currentEvents = emptyList<NostrEvent>()
    private var currentProfiles = emptyMap<String, NostrProfile>()
    private var currentReactionCounts = emptyMap<String, Int>()
    private var currentLikeReactionCounts = emptyMap<String, Int>()
    private var currentCustomReactions = emptyMap<String, List<CustomReaction>>()
    private var currentUnicodeReactions = emptyMap<String, List<UnicodeReaction>>()
    private var currentReactionEvents = emptyMap<String, List<NostrEvent>>()
    private var currentReplyCounts = emptyMap<String, Int>()
    private var currentRepostCounts = emptyMap<String, Int>()
    private var currentRepostPubkeys = emptyMap<String, List<String>>()
    private var currentUsers = emptyList<Pair<String, NostrProfile>>()

    private var oldestCreatedAt: Long? = null
    private var loadingMore = false
    private var activePageSubId: String? = null
    private val pageProgress = SearchPageProgress()
    private val temporaryPageSubIds = linkedSetOf<String>()

    private val postJobs = mutableListOf<Job>()
    private val pageJobs = mutableMapOf<String, MutableList<Job>>()
    private var userSearchJob: Job? = null
    private var profileBatchJob: Job? = null
    private var engagementBatchJob: Job? = null
    private var engagementFetchJob: Job? = null
    private var pageRequestJob: Job? = null
    private var pageTimeoutJob: Job? = null
    private var stateSyncJob: Job? = null

    fun selectTab(tab: SearchTab) {
        if (_state.value.selectedTab != tab) _state.value = _state.value.copy(selectedTab = tab)
        if (currentQuery.isBlank()) return
        when (tab) {
            SearchTab.Posts -> if (_state.value.postsLoadState in setOf(SearchLoadState.NotRequested, SearchLoadState.Interrupted)) startPostSearch(pageProgress.interruptedUntil)
            SearchTab.Users -> if (_state.value.usersLoadState in setOf(SearchLoadState.NotRequested, SearchLoadState.Interrupted)) startUserSearch()
        }
    }

    fun search(query: String, tab: SearchTab = _state.value.selectedTab) {
        val trimmed = query.trim()
        networkTraceLog { "[Search] search() called: query='$trimmed' tab=$tab" }
        if (trimmed.isBlank()) return

        stopSubscriptions(normalizeLoadingState = false)
        searchGeneration++
        pageSerial = 0
        engagementSerial = 0
        currentQuery = trimmed
        resetResults()
        _state.value = UiState(query = trimmed, selectedTab = tab)
        when (tab) {
            SearchTab.Posts -> startPostSearch()
            SearchTab.Users -> startUserSearch()
        }
    }

    fun loadMore() {
        if (loadingMore || _state.value.postsLoadState != SearchLoadState.Ready || !_state.value.canLoadMore) return
        pageRequestJob = launch { requestPage(until = oldestCreatedAt?.minus(1)) }
    }

    /** 画面が非表示になった時点で、検索画面が所有する通信と保留処理を止める。 */
    fun stop() = stopSubscriptions(normalizeLoadingState = true)

    /** 画面復帰時、停止した有限取得だけを同じページ境界から再開する。 */
    fun resume() {
        when (_state.value.selectedTab) {
            SearchTab.Posts -> if (_state.value.postsLoadState == SearchLoadState.Interrupted) startPostSearch(pageProgress.interruptedUntil)
            SearchTab.Users -> if (_state.value.usersLoadState == SearchLoadState.Interrupted) startUserSearch()
        }
    }

    private fun startUserSearch() {
        if (currentQuery.isBlank() || userSearchJob?.isActive == true) return
        val generation = searchGeneration
        val query = currentQuery
        _state.value = _state.value.copy(usersLoadState = SearchLoadState.Loading)
        userSearchJob = launch {
            try {
                val users = ProfileRepository.searchProfiles(query, SEARCH_RELAY_URL, USER_SEARCH_LIMIT)
                if (generation != searchGeneration || query != currentQuery) return@launch
                users.forEach { (pubkey, profile) ->
                    if (seenUserIds.add(pubkey)) currentUsers = currentUsers + (pubkey to profile)
                }
                publishState(usersLoadState = SearchLoadState.Ready)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (generation == searchGeneration && query == currentQuery) {
                    publishState(usersLoadState = SearchLoadState.Failed)
                }
            }
        }
    }

    private fun startPostSearch(until: Long? = null) {
        if (currentQuery.isBlank() || _state.value.postsLoadState == SearchLoadState.Loading) return
        val generation = searchGeneration
        _state.value = _state.value.copy(postsLoadState = SearchLoadState.Loading)
        postJobs += launch {
            ProfileRepository.observeChanges().collect { changedPubkeys ->
                if (generation != searchGeneration) return@collect
                val targets = pendingPubkeys + currentProfiles.keys
                val affected = if (changedPubkeys.isEmpty()) targets else changedPubkeys.intersect(targets)
                if (affected.isEmpty()) return@collect
                val profiles = currentProfiles - affected + ProfileRepository.getCached(affected)
                if (profiles != currentProfiles) {
                    currentProfiles = profiles
                    scheduleStateSync()
                }
            }
        }
        pageRequestJob = launch { requestPage(until = until) }
    }

    private suspend fun requestPage(until: Long?) {
        if (loadingMore || currentQuery.isBlank()) return
        loadingMore = true
        if (until != null) _state.value = _state.value.copy(canLoadMore = false)

        val generation = searchGeneration
        val subId = "srch-$generation-${++pageSerial}"
        activePageSubId = subId
        pageProgress.start(until)
        temporaryPageSubIds += subId
        val collectors = mutableListOf<Job>()
        pageJobs[subId] = collectors
        collectors += launch {
            NostrRepository.events(subId).collect { event ->
                if (generation != searchGeneration || subId != activePageSubId || event.kind != 1) return@collect
                val added = appendEvent(event)
                pageProgress.observe(event.id, event.createdAt)
                if (added > 0) {
                    scheduleProfileFetch(event.pubkey)
                    scheduleMentionedProfileFetch(event.content)
                    scheduleEngagementFetch(event.id)
                }
            }
        }
        collectors += launch {
            NostrRepository.eose(subId).collect {
                if (generation == searchGeneration && subId == activePageSubId) onPageCompleted(subId)
            }
        }
        schedulePageTimeout(subId, generation)

        val filter = NostrFilter(kinds = listOf(1), search = currentQuery, until = until, limit = PAGE_SIZE)
        networkTraceLog { "[Search] requestPage() subId=$subId until=$until filter=$filter" }
        try {
            NostrRepository.subscribeTemporaryRelay(subId, filter, SEARCH_RELAY_URL)
        } catch (error: CancellationException) {
            closeTemporaryPage(subId)
            throw error
        } catch (_: Throwable) {
            if (generation == searchGeneration && subId == activePageSubId) {
                loadingMore = false
                activePageSubId = null
                pageProgress.complete()
                pageTimeoutJob?.cancel()
                pageTimeoutJob = null
                closeTemporaryPage(subId)
                closePageCollectors(subId)
                publishState(postsLoadState = SearchLoadState.Failed, canLoadMore = false)
            }
        }
    }

    private fun onPageCompleted(subId: String) {
        if (!loadingMore || subId != activePageSubId) return
        loadingMore = false
        activePageSubId = null
        val batch = pageProgress.complete()
        oldestCreatedAt = batch.oldestCreatedAt ?: oldestCreatedAt
        pageTimeoutJob?.cancel()
        pageTimeoutJob = null
        val hasMore = batch.count >= PAGE_SIZE
        closeTemporaryPage(subId)
        closePageCollectors(subId)
        publishState(postsLoadState = SearchLoadState.Ready, canLoadMore = hasMore)
    }

    private fun schedulePageTimeout(subId: String, generation: Int) {
        pageTimeoutJob?.cancel()
        pageTimeoutJob = launch {
            delay(PAGE_TIMEOUT_MS)
            if (!loadingMore || generation != searchGeneration || subId != activePageSubId) return@launch
            loadingMore = false
            activePageSubId = null
            val batch = pageProgress.complete()
            oldestCreatedAt = batch.oldestCreatedAt ?: oldestCreatedAt
            val hasMore = batch.count >= PAGE_SIZE
            closeTemporaryPage(subId)
            closePageCollectors(subId)
            publishState(postsLoadState = SearchLoadState.Ready, canLoadMore = hasMore)
        }
    }

    private fun closeTemporaryPage(subId: String) {
        temporaryPageSubIds.remove(subId)
        NostrRepository.closeTemporaryRelay(subId)
    }

    private fun closePageCollectors(subId: String) {
        pageJobs.remove(subId)?.forEach(Job::cancel)
    }

    private fun appendEvent(event: NostrEvent): Int {
        if (!seenEventIds.add(event.id)) return 0
        currentEvents = (currentEvents + event).sortedByDescending { it.createdAt }
        oldestCreatedAt = currentEvents.lastOrNull()?.createdAt
        scheduleStateSync()
        return 1
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in currentProfiles || pubkey in pendingPubkeys) return
        pendingPubkeys += pubkey
        ProfileRepository.getCached(pubkey)?.let { profile ->
            currentProfiles = currentProfiles + (pubkey to profile)
            scheduleStateSync()
        }
        profileBatchJob?.cancel()
        profileBatchJob = launch {
            delay(PROFILE_BATCH_MS)
            if (pendingPubkeys.isNotEmpty()) {
                ProfileRepository.ensureProfiles(
                    pendingPubkeys.toSet(),
                    ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS),
                )
            }
        }
    }

    private fun scheduleMentionedProfileFetch(text: String) {
        extractNpubReferences(text).forEach { scheduleProfileFetch(it.pubkey) }
    }

    private fun scheduleEngagementFetch(eventId: String) {
        if (!watchedEventIds.add(eventId)) return
        while (watchedEventIds.size > MAX_TRACKED_ENGAGEMENT_EVENTS) watchedEventIds.remove(watchedEventIds.first())
        engagementBatchJob?.cancel()
        engagementBatchJob = launch {
            delay(ENGAGEMENT_BATCH_MS)
            startEngagementFetch(watchedEventIds.toList(), searchGeneration)
        }
    }

    private fun startEngagementFetch(eventIds: List<String>, generation: Int) {
        if (eventIds.isEmpty() || generation != searchGeneration) return
        engagementFetchJob?.cancel()
        val subId = "sengagement-$generation-${++engagementSerial}"
        val since = Clock.System.now().epochSeconds - ENGAGEMENT_LOOKBACK_SECONDS
        engagementFetchJob = launch {
            val session = NostrRepository.openSubscription(
                SubscriptionSpec(
                    id = subId,
                    filters = engagementFilters(eventIds, since),
                    behavior = SubscriptionBehavior.Fetch(ENGAGEMENT_TIMEOUT_MS),
                ),
            )
            var receivedCount = 0
            try {
                session.signals.collect { signal ->
                    if (generation != searchGeneration) return@collect
                    when (signal) {
                        is SubscriptionSignal.Event -> {
                            applyEngagementEvent(signal.event)
                            receivedCount++
                            if (receivedCount >= ENGAGEMENT_TOTAL_LIMIT) session.close()
                        }
                        is SubscriptionSignal.FetchCompleted -> publishState()
                        else -> Unit
                    }
                }
            } finally {
                session.close()
            }
        }
    }

    private fun applyEngagementEvent(event: NostrEvent) {
        when (event.kind) {
            7 -> applyReaction(event)
            6 -> applyRepost(event)
            COMMENT_EVENT_KIND -> if (event.isSupportedTimelineComment()) applyReply(event)
            1 -> {
                applyReply(event)
                applyQuoteRepost(event)
            }
        }
        scheduleStateSync()
    }

    private fun applyReaction(event: NostrEvent) {
        if (!rememberSeenId(seenReactionIds, event.id)) return
        val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
            ?.takeIf { it in watchedEventIds } ?: return
        currentReactionCounts = currentReactionCounts + (targetId to (currentReactionCounts[targetId] ?: 0) + 1)
        if (event.content.trim() == "+") {
            currentLikeReactionCounts = currentLikeReactionCounts +
                (targetId to (currentLikeReactionCounts[targetId] ?: 0) + 1)
        }
        event.toCustomReaction()?.let { reaction ->
            currentCustomReactions = currentCustomReactions +
                (targetId to currentCustomReactions[targetId].orEmpty().incrementedWith(reaction))
        }
        event.toUnicodeReaction()?.let { reaction ->
            currentUnicodeReactions = currentUnicodeReactions +
                (targetId to currentUnicodeReactions[targetId].orEmpty().incrementedWithUnicodeReaction(reaction))
        }
        currentReactionEvents = currentReactionEvents +
            (targetId to currentReactionEvents[targetId].orEmpty().plus(event))
        scheduleProfileFetch(event.pubkey)
    }

    private fun applyReply(event: NostrEvent) {
        if (!rememberSeenId(seenReplyIds, event.id)) return
        val targetId = event.replyTargetId()?.takeIf { it in watchedEventIds } ?: return
        currentReplyCounts = currentReplyCounts + (targetId to (currentReplyCounts[targetId] ?: 0) + 1)
    }

    private fun applyRepost(event: NostrEvent) {
        if (!rememberSeenId(seenRepostIds, event.id)) return
        val targetId = event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
            ?.takeIf { it in watchedEventIds } ?: return
        currentRepostCounts = currentRepostCounts + (targetId to (currentRepostCounts[targetId] ?: 0) + 1)
        currentRepostPubkeys = currentRepostPubkeys +
            (targetId to currentRepostPubkeys[targetId].orEmpty().plus(event.pubkey).distinct())
        scheduleProfileFetch(event.pubkey)
    }

    private fun applyQuoteRepost(event: NostrEvent) {
        val targetIds = event.tags.filter { it.firstOrNull() == "q" }.mapNotNull { it.getOrNull(1) }
            .distinct().filter { it in watchedEventIds }
            .filter { rememberSeenId(seenQuoteRepostIds, "${event.id}:$it") }
        if (targetIds.isEmpty()) return
        val counts = currentRepostCounts.toMutableMap()
        targetIds.forEach { targetId ->
            counts[targetId] = (counts[targetId] ?: 0) + 1
            currentRepostPubkeys = currentRepostPubkeys +
                (targetId to currentRepostPubkeys[targetId].orEmpty().plus(event.pubkey).distinct())
        }
        currentRepostCounts = counts
        scheduleProfileFetch(event.pubkey)
    }

    private fun scheduleStateSync() {
        if (stateSyncJob?.isActive == true) return
        stateSyncJob = launch {
            delay(STATE_SYNC_BATCH_MS)
            publishState()
        }
    }

    private fun publishState(
        postsLoadState: SearchLoadState = _state.value.postsLoadState,
        usersLoadState: SearchLoadState = _state.value.usersLoadState,
        canLoadMore: Boolean = _state.value.canLoadMore,
    ) {
        stateSyncJob?.cancel()
        stateSyncJob = null
        _state.value = _state.value.copy(
            query = currentQuery,
            postsLoadState = postsLoadState,
            usersLoadState = usersLoadState,
            events = currentEvents,
            profiles = currentProfiles,
            reactionCounts = currentReactionCounts,
            likeReactionCounts = currentLikeReactionCounts,
            customReactions = currentCustomReactions,
            unicodeReactions = currentUnicodeReactions,
            reactionEvents = currentReactionEvents,
            replyCounts = currentReplyCounts,
            repostCounts = currentRepostCounts,
            repostPubkeys = currentRepostPubkeys,
            canLoadMore = canLoadMore,
            users = currentUsers,
        )
    }

    private fun rememberSeenId(seenIds: LinkedHashSet<String>, eventId: String): Boolean {
        if (!seenIds.add(eventId)) return false
        while (seenIds.size > MAX_SEEN_IDS) seenIds.remove(seenIds.first())
        return true
    }

    private fun resetResults() {
        seenEventIds.clear()
        seenUserIds.clear()
        pendingPubkeys.clear()
        watchedEventIds.clear()
        seenReactionIds.clear()
        seenReplyIds.clear()
        seenRepostIds.clear()
        seenQuoteRepostIds.clear()
        currentEvents = emptyList()
        currentProfiles = emptyMap()
        currentReactionCounts = emptyMap()
        currentLikeReactionCounts = emptyMap()
        currentCustomReactions = emptyMap()
        currentUnicodeReactions = emptyMap()
        currentReactionEvents = emptyMap()
        currentReplyCounts = emptyMap()
        currentRepostCounts = emptyMap()
        currentRepostPubkeys = emptyMap()
        currentUsers = emptyList()
        oldestCreatedAt = null
        loadingMore = false
        activePageSubId = null
        pageProgress.reset()
    }

    private fun stopSubscriptions(normalizeLoadingState: Boolean) {
        if (normalizeLoadingState && activePageSubId != null && loadingMore) pageProgress.interrupt()
        if (!normalizeLoadingState) pageProgress.reset()
        userSearchJob?.cancel()
        userSearchJob = null
        postJobs.forEach(Job::cancel)
        postJobs.clear()
        pageJobs.values.flatten().forEach(Job::cancel)
        pageJobs.clear()
        profileBatchJob?.cancel()
        profileBatchJob = null
        engagementBatchJob?.cancel()
        engagementBatchJob = null
        engagementFetchJob?.cancel()
        engagementFetchJob = null
        pageRequestJob?.cancel()
        pageRequestJob = null
        pageTimeoutJob?.cancel()
        pageTimeoutJob = null
        stateSyncJob?.cancel()
        stateSyncJob = null
        temporaryPageSubIds.toList().forEach(::closeTemporaryPage)
        activePageSubId = null
        loadingMore = false

        if (normalizeLoadingState) {
            val state = _state.value
            _state.value = state.copy(
                postsLoadState = if (state.postsLoadState == SearchLoadState.Loading) SearchLoadState.Interrupted
                    else state.postsLoadState,
                usersLoadState = if (state.usersLoadState == SearchLoadState.Loading) {
                    SearchLoadState.Interrupted
                } else state.usersLoadState,
                events = currentEvents,
                users = currentUsers,
            )
        }
    }

    override fun onCleared() {
        stopSubscriptions(normalizeLoadingState = false)
        super.onCleared()
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val USER_SEARCH_LIMIT = 20
        private const val MAX_SEEN_IDS = 2_000
        private const val MAX_TRACKED_ENGAGEMENT_EVENTS = 100
        internal const val ENGAGEMENT_FILTER_LIMIT = 200
        private const val ENGAGEMENT_TOTAL_LIMIT = 800
        private const val ENGAGEMENT_LOOKBACK_SECONDS = 90L * 24L * 60L * 60L
        private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
        private const val PROFILE_BATCH_MS = 500L
        private const val ENGAGEMENT_BATCH_MS = 500L
        private const val STATE_SYNC_BATCH_MS = 100L
        private const val PAGE_TIMEOUT_MS = 10_000L
        private const val ENGAGEMENT_TIMEOUT_MS = 5_000L
        private const val SEARCH_RELAY_URL = "wss://search.nos.today"

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                SearchViewModel() as T
        }
    }
}

internal fun engagementFilters(eventIds: List<String>, since: Long): List<NostrFilter> = listOf(
    NostrFilter(kinds = listOf(7), eTags = eventIds, since = since, limit = SearchViewModel.ENGAGEMENT_FILTER_LIMIT),
    NostrFilter(kinds = listOf(1), eTags = eventIds, since = since, limit = SearchViewModel.ENGAGEMENT_FILTER_LIMIT),
    NostrFilter(
        kinds = listOf(COMMENT_EVENT_KIND),
        rootKindTags = listOf("1"),
        eTags = eventIds,
        since = since,
        limit = SearchViewModel.ENGAGEMENT_FILTER_LIMIT,
    ),
    NostrFilter(
        kinds = listOf(COMMENT_EVENT_KIND),
        rootKindTags = listOf("1"),
        rootEventTags = eventIds,
        since = since,
        limit = SearchViewModel.ENGAGEMENT_FILTER_LIMIT,
    ),
    NostrFilter(kinds = listOf(6), eTags = eventIds, since = since, limit = SearchViewModel.ENGAGEMENT_FILTER_LIMIT),
    NostrFilter(kinds = listOf(1), qTags = eventIds, since = since, limit = SearchViewModel.ENGAGEMENT_FILTER_LIMIT),
)
