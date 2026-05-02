package com.nostr.torinos.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nostr.torinos.ui.SafeViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.util.appLog
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
            val canLoadMore: Boolean = false,
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
    private var currentEvents = emptyList<NostrEvent>()
    private var currentProfiles = emptyMap<String, NostrProfile>()

    private val searchSubId = "srch-main"
    private val profileSubId = "sprof-main"

    fun search(query: String) {
        val trimmed = query.trim()
        appLog("[Search] search() called: query='$trimmed'")
        if (trimmed.isBlank()) {
            appLog("[Search] query is blank, ignoring")
            return
        }

        stopSubscriptions()

        currentQuery = trimmed
        seenEventIds.clear()
        pendingPubkeys.clear()
        oldestCreatedAt = null
        loadingMore = false
        lastBatchCount = 0
        receivedEoseCount = 0
        currentEvents = emptyList()
        currentProfiles = emptyMap()
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
        appLog("[Search] startSubscriptions() relayCount=${NostrRepository.relayCount}")

        // 検索結果イベントを収集
        subscriptionJobs += launch {
            NostrRepository.events(searchSubId).collect { event ->
                if (event.kind != 1) return@collect
                val added = appendEvent(event)
                lastBatchCount += added
                appLog("[Search] event received id=${event.id.take(8)} pubkey=${event.pubkey.take(8)} added=$added totalSeen=${seenEventIds.size}")
                scheduleProfileFetch(event.pubkey)
                scheduleMentionedProfileFetch(event.content)
            }
        }

        // EOSE でページ完了
        subscriptionJobs += launch {
            NostrRepository.eose(searchSubId).collect {
                receivedEoseCount++
                appLog("[Search] EOSE received receivedEoseCount=$receivedEoseCount expectedEoseCount=$expectedEoseCount lastBatchCount=$lastBatchCount")
                if (receivedEoseCount >= expectedEoseCount) {
                    onPageCompleted()
                }
            }
        }

        // 10秒タイムアウトフォールバック
        subscriptionJobs += launch {
            delay(10_000)
            if (_state.value is UiState.Loading) {
                appLog("[Search] timeout: no EOSE received in 10s, forcing Ready state")
                _state.value = readyState(canLoadMore = false)
            }
        }

        // プロフィール受信（kind:0）
        subscriptionJobs += launch {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                appLog("[Search] profile received pubkey=${event.pubkey.take(8)} name=${profile.name}")
                pendingPubkeys.remove(event.pubkey)
                currentProfiles = currentProfiles + (event.pubkey to profile)
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
        NostrRepository.close(searchSubId)
        NostrRepository.close(profileSubId)
    }

    override fun onCleared() {
        super.onCleared()
        stopSubscriptions()
    }

    private suspend fun requestPage(until: Long?) {
        loadingMore = true
        lastBatchCount = 0
        receivedEoseCount = 0
        expectedEoseCount = NostrRepository.relayCount.coerceAtLeast(1)
        val current = _state.value as? UiState.Ready
        if (current != null) {
            _state.value = current.copy(canLoadMore = false)
        }

        val filter = buildFilter(until)
        appLog("[Search] requestPage() until=$until expectedEoseCount=$expectedEoseCount filter=$filter")
        NostrRepository.subscribe(searchSubId, filter)
    }

    private fun buildFilter(until: Long?): NostrFilter {
        val q = currentQuery
        return if (q.startsWith("#")) {
            val tag = q.removePrefix("#").lowercase()
            appLog("[Search] buildFilter: hashtag mode tag='$tag' until=$until")
            NostrFilter(kinds = listOf(1), tTags = listOf(tag), until = until, limit = PAGE_SIZE)
        } else {
            appLog("[Search] buildFilter: keyword mode search='$q' until=$until")
            NostrFilter(kinds = listOf(1), search = q, until = until, limit = PAGE_SIZE)
        }
    }

    private fun onPageCompleted() {
        loadingMore = false
        val hasMore = lastBatchCount >= PAGE_SIZE
        appLog("[Search] onPageCompleted() lastBatchCount=$lastBatchCount hasMore=$hasMore totalEvents=${currentEvents.size}")
        _state.value = readyState(canLoadMore = hasMore)
        if (!hasMore) NostrRepository.close(searchSubId)
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
            canLoadMore = canLoadMore,
        )

    private fun syncReadyState() {
        val current = _state.value as? UiState.Ready ?: return
        _state.value = current.copy(
            query = currentQuery,
            events = currentEvents,
            profiles = currentProfiles,
        )
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

    private fun scheduleMentionedProfileFetch(text: String) {
        extractNpubReferences(text).forEach { reference ->
            scheduleProfileFetch(reference.pubkey)
        }
    }

    companion object {
        private const val PAGE_SIZE = 30

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                SearchViewModel() as T
        }
    }
}
