package com.nostr.torinos.ui.profile

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.network.NostrRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UserProfileState(
    val profile: NostrProfile? = null,
    val events: List<NostrEvent> = emptyList(),
    val isLoading: Boolean = true,
    val canLoadMore: Boolean = false,
    /** null = フォローリスト未ロード */
    val isFollowing: Boolean? = null,
    val isFollowLoading: Boolean = false,
    val followError: String? = null,
    val canFollow: Boolean = false,
)

class UserProfileViewModel(private val pubkey: String) : SafeViewModel() {
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
        if (isWriteSupported) observeFollowState()
    }

    // ---- フォロー関連 ----

    private fun observeFollowState() {
        collectorJobs += launch {
            combine(
                FollowRepository.followedPubkeys,
                FollowRepository.loaded,
            ) { follows, loaded ->
                Pair(follows, loaded)
            }.collect { (follows, loaded) ->
                if (loaded) {
                    _state.update { it.copy(isFollowing = follows.contains(pubkey), canFollow = true) }
                }
            }
        }
    }

    fun follow() {
        if (_state.value.isFollowLoading) return
        _state.update { it.copy(isFollowLoading = true, followError = null) }
        launch {
            FollowRepository.follow(pubkey)
                .onSuccess { _state.update { it.copy(isFollowLoading = false) } }
                .onFailure { e -> _state.update { it.copy(isFollowLoading = false, followError = e.message) } }
        }
    }

    fun unfollow() {
        if (_state.value.isFollowLoading) return
        _state.update { it.copy(isFollowLoading = true, followError = null) }
        launch {
            FollowRepository.unfollow(pubkey)
                .onSuccess { _state.update { it.copy(isFollowLoading = false) } }
                .onFailure { e -> _state.update { it.copy(isFollowLoading = false, followError = e.message) } }
        }
    }

    fun clearFollowError() { _state.update { it.copy(followError = null) } }

    fun loadMore() {
        if (loadingMore || !_state.value.canLoadMore) return
        launch {
            requestHistoryPage(until = oldestCreatedAt?.minus(1))
        }
    }

    private fun start() {
        startCollectors()
        timeoutJob = launch {
            delay(10_000)
            _state.update { current -> current.copy(isLoading = false) }
            startLiveSubscription()
        }
        launch {
            NostrRepository.subscribe(
                profileSubId,
                NostrFilter(kinds = listOf(0), authors = listOf(pubkey), limit = 1),
            )
            requestHistoryPage(until = null)
        }
    }

    private fun startCollectors() {
        collectorJobs += launch {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                _state.update { it.copy(profile = profile) }
            }
        }

        collectorJobs += launch {
            NostrRepository.events(historySubId).collect { event ->
                if (event.kind != 1) return@collect
                lastHistoryBatchUniqueCount += appendEvent(event)
            }
        }

        collectorJobs += launch {
            NostrRepository.events(liveSubId).collect { event ->
                if (event.kind != 1) return@collect
                appendEvent(event)
            }
        }

        collectorJobs += launch {
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
        launch {
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
