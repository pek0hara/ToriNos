package com.example.nostr.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nostr.model.NostrEvent
import com.example.nostr.model.NostrFilter
import com.example.nostr.model.NostrProfile
import com.example.nostr.model.toProfile
import com.example.nostr.network.NostrRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UserProfileState(
    val profile: NostrProfile? = null,
    val events: List<NostrEvent> = emptyList(),
    val isLoading: Boolean = true,
    val canLoadMore: Boolean = false,
)

class UserProfileViewModel(private val pubkey: String) : ViewModel() {
    private val _state = MutableStateFlow(UserProfileState())
    val state: StateFlow<UserProfileState> = _state.asStateFlow()

    private val shortKey = pubkey.take(16)
    private val profileSubId = "up-$shortKey"
    private val historySubId = "uh-$shortKey"
    private val liveSubId = "ul-$shortKey"

    private val seenEventIds = linkedSetOf<String>()
    private val collectorJobs = mutableListOf<Job>()
    private var timeoutJob: Job? = null
    private var loadingMore = false
    private var liveStarted = false
    private var oldestCreatedAt: Long? = null
    private var lastHistoryBatchUniqueCount = 0

    // 全リレーの EOSE 待ち合わせ用カウンター
    private var expectedEoseCount = 1
    private var receivedEoseCount = 0

    init {
        start()
    }

    fun loadMore() {
        if (loadingMore || !_state.value.canLoadMore) return
        viewModelScope.launch {
            requestHistoryPage(until = oldestCreatedAt?.minus(1))
        }
    }

    private fun start() {
        startCollectors()
        timeoutJob = viewModelScope.launch {
            delay(10_000)
            _state.update { current -> current.copy(isLoading = false) }
            startLiveSubscription()
        }
        viewModelScope.launch {
            NostrRepository.subscribe(
                profileSubId,
                NostrFilter(kinds = listOf(0), authors = listOf(pubkey), limit = 1),
            )
            requestHistoryPage(until = null)
        }
    }

    private fun startCollectors() {
        collectorJobs += viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                _state.update { it.copy(profile = profile) }
            }
        }

        collectorJobs += viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.events(historySubId).collect { event ->
                if (event.kind != 1) return@collect
                lastHistoryBatchUniqueCount += appendEvent(event)
            }
        }

        collectorJobs += viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.events(liveSubId).collect { event ->
                if (event.kind != 1) return@collect
                appendEvent(event)
            }
        }

        collectorJobs += viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.eose(historySubId).collect {
                receivedEoseCount++
if (receivedEoseCount >= expectedEoseCount) {
                    onHistoryPageCompleted()
                }
            }
        }
    }

    private suspend fun requestHistoryPage(until: Long?) {
        loadingMore = true
        lastHistoryBatchUniqueCount = 0
        receivedEoseCount = 0
        expectedEoseCount = NostrRepository.relayCount.coerceAtLeast(1)
_state.update { current ->
            current.copy(
                isLoading = current.events.isEmpty(),
                canLoadMore = false,
            )
        }
        NostrRepository.subscribe(
            historySubId,
            NostrFilter(
                kinds = listOf(1),
                authors = listOf(pubkey),
                until = until,
                limit = HISTORY_PAGE_SIZE,
            ),
        )
    }

    private fun onHistoryPageCompleted() {
        loadingMore = false
        val hasMore = lastHistoryBatchUniqueCount >= HISTORY_PAGE_SIZE
_state.update { current ->
            current.copy(
                isLoading = false,
                canLoadMore = hasMore,
            )
        }
        if (!hasMore) {
            NostrRepository.close(historySubId)
            startLiveSubscription()
        }
        // hasMore の場合は canLoadMore = true のまま待機し、
        // ユーザーが "もっと読み込む" を押したときに loadMore() が呼ばれる
    }

    private fun startLiveSubscription() {
        if (liveStarted) return
        liveStarted = true
        viewModelScope.launch {
            NostrRepository.subscribe(
                liveSubId,
                NostrFilter(
                    kinds = listOf(1),
                    authors = listOf(pubkey),
                    since = _state.value.events.maxOfOrNull { it.createdAt }?.plus(1),
                ),
            )
        }
    }

    private fun appendEvent(event: NostrEvent): Int {
        if (!seenEventIds.add(event.id)) return 0
        _state.update { current ->
            val updated = (current.events + event)
                .distinctBy { it.id }
                .sortedByDescending { it.createdAt }
            oldestCreatedAt = updated.lastOrNull()?.createdAt
            current.copy(
                events = updated,
                isLoading = false,
            )
        }
        timeoutJob?.cancel()
        return 1
    }

    override fun onCleared() {
        super.onCleared()
        collectorJobs.forEach { it.cancel() }
        timeoutJob?.cancel()
        NostrRepository.close(profileSubId)
        NostrRepository.close(historySubId)
        NostrRepository.close(liveSubId)
    }

    companion object {
        private const val HISTORY_PAGE_SIZE = 20
    }
}
