package com.nostr.torinos.ui.profile

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.loadPublicKey
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MyProfileState(
    val ownPubkey: String = "",
    val profile: NostrProfile? = null,
    val followingCount: Int = 0,
    val followersCount: Int = 0,
    val events: List<NostrEvent> = emptyList(),
    val isLoading: Boolean = true,
    val canLoadMore: Boolean = false,
)

class MyProfileViewModel : SafeViewModel() {
    private val _state = MutableStateFlow(MyProfileState())
    val state: StateFlow<MyProfileState> = _state.asStateFlow()

    private var ownPubkey: String = ""
    private val profileSubId = "mp-profile"
    private val historySubId = "mp-history"
    private val liveSubId   = "mp-live"
    private val followerSubId = "mp-followers"

    private val seenEventIds = linkedSetOf<String>()
    private val followerPubkeys = linkedSetOf<String>()
    private val collectorJobs = mutableListOf<Job>()
    private var timeoutJob: Job? = null
    private var loadingMore = false
    private var liveStarted = false
    private var oldestCreatedAt: Long? = null
    private var lastHistoryBatchUniqueCount = 0
    private var expectedEoseCount = 1
    private var receivedEoseCount = 0

    init {
        launch { start() }
    }

    private suspend fun start() {
        ownPubkey = loadPublicKey() ?: return
        _state.update { it.copy(ownPubkey = ownPubkey) }

        startCollectors()

        // フォロー数をFollowRepositoryから取得
        collectorJobs += launch {
            FollowRepository.followedPubkeys.collect { follows ->
                _state.update { it.copy(followingCount = follows.size) }
            }
        }

        timeoutJob = launch {
            delay(10_000)
            _state.update { it.copy(isLoading = false) }
            startLiveSubscription()
        }

        NostrRepository.subscribe(
            profileSubId,
            NostrFilter(kinds = listOf(0), authors = listOf(ownPubkey), limit = 1),
        )
        // フォロワー取得: 自分のpubkeyをpタグに持つkind:3
        NostrRepository.subscribe(
            followerSubId,
            NostrFilter(kinds = listOf(3), pTags = listOf(ownPubkey), limit = 1000),
        )
        requestHistoryPage(until = null)
    }

    private fun startCollectors() {
        collectorJobs += launch {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                event.toProfile()?.let { profile ->
                    _state.update { it.copy(profile = profile) }
                }
            }
        }

        collectorJobs += launch {
            NostrRepository.events(followerSubId).collect { event ->
                if (event.kind != 3) return@collect
                if (followerPubkeys.add(event.pubkey)) {
                    _state.update { it.copy(followersCount = followerPubkeys.size) }
                }
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
                if (receivedEoseCount >= expectedEoseCount) onHistoryPageCompleted()
            }
        }
    }

    private suspend fun requestHistoryPage(until: Long?) {
        if (ownPubkey.isEmpty()) return
        loadingMore = true
        lastHistoryBatchUniqueCount = 0
        receivedEoseCount = 0
        expectedEoseCount = NostrRepository.relayCount.coerceAtLeast(1)
        _state.update { it.copy(isLoading = it.events.isEmpty(), canLoadMore = false) }
        NostrRepository.subscribe(
            historySubId,
            NostrFilter(kinds = listOf(1), authors = listOf(ownPubkey), until = until, limit = PAGE_SIZE),
        )
    }

    private fun onHistoryPageCompleted() {
        loadingMore = false
        val hasMore = lastHistoryBatchUniqueCount >= PAGE_SIZE
        _state.update { it.copy(isLoading = false, canLoadMore = hasMore) }
        if (!hasMore) {
            NostrRepository.close(historySubId)
            startLiveSubscription()
        }
    }

    private fun startLiveSubscription() {
        if (liveStarted || ownPubkey.isEmpty()) return
        liveStarted = true
        launch {
            NostrRepository.subscribe(
                liveSubId,
                NostrFilter(
                    kinds = listOf(1),
                    authors = listOf(ownPubkey),
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
            current.copy(events = updated, isLoading = false)
        }
        timeoutJob?.cancel()
        return 1
    }

    /** 編集保存後に UI を即時更新する（リレーの応答を待たない）。 */
    fun applyProfile(profile: NostrProfile) {
        _state.update { it.copy(profile = profile) }
        // バックグラウンドでリレーからも再取得しておく（遅延で競合回避）
        if (ownPubkey.isEmpty()) return
        launch {
            delay(2_000)
            NostrRepository.close(profileSubId)
            NostrRepository.subscribe(
                profileSubId,
                NostrFilter(kinds = listOf(0), authors = listOf(ownPubkey), limit = 1),
            )
        }
    }

    fun loadMore() {
        if (loadingMore || !_state.value.canLoadMore) return
        launch { requestHistoryPage(until = oldestCreatedAt?.minus(1)) }
    }

    override fun onCleared() {
        super.onCleared()
        collectorJobs.forEach { it.cancel() }
        timeoutJob?.cancel()
        NostrRepository.close(profileSubId)
        NostrRepository.close(historySubId)
        NostrRepository.close(liveSubId)
        NostrRepository.close(followerSubId)
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}
