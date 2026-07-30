package com.nostr.torinos.ui.profile

import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileCache
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FollowListMode { FOLLOWING, FOLLOWERS }

data class FollowListState(
    val entries: List<Pair<String, NostrProfile?>> = emptyList(),
    val isLoading: Boolean = true,
)

class FollowListViewModel(
    private val mode: FollowListMode,
    private val ownPubkey: String,
) : SafeViewModel() {

    private val _state = MutableStateFlow(FollowListState())
    val state: StateFlow<FollowListState> = _state.asStateFlow()

    private val profileSubId = "fl-profiles-${mode.name}-${ownPubkey.take(8)}"
    private val followerSubId = "fl-followers-${ownPubkey.take(8)}"
    private val followingListSubId = "fl-kind3-${ownPubkey.take(16)}"
    private val collectorJobs = mutableListOf<Job>()
    private val activeProfileSubIds = mutableSetOf<String>()
    private val queuedProfilePubkeys = linkedSetOf<String>()
    private val requestedProfilePubkeys = mutableSetOf<String>()

    // pubkey → profile
    private val profileMap = mutableMapOf<String, NostrProfile?>()
    // 取得済みpubkeyセット
    private val knownPubkeys = linkedSetOf<String>()
    private var profileRequestSerial = 0
    private var pendingProfileSubscriptions = 0
    private var profileBatchJob: Job? = null
    private var publishJob: Job? = null

    init {
        launch { start() }
    }

    private suspend fun start() {
        when (mode) {
            FollowListMode.FOLLOWING -> loadFollowing()
            FollowListMode.FOLLOWERS -> loadFollowers()
        }
    }

    private suspend fun loadFollowing() {
        var latestAt = -1L
        var latestPubkeys: List<String> = emptyList()

        collectorJobs += launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.events(followingListSubId).collect { event ->
                if (event.kind != 3) return@collect
                if (event.createdAt > latestAt) {
                    latestAt = event.createdAt
                    latestPubkeys = event.tags
                        .filter { it.size >= 2 && it[0] == "p" }
                        .map { it[1] }
                        .distinct()
                }
            }
        }

        collectorJobs += launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.eose(followingListSubId).collect {
                NostrRepository.close(followingListSubId)
                val pubkeys = latestPubkeys
                if (pubkeys.isEmpty()) {
                    _state.update { it.copy(isLoading = false) }
                    return@collect
                }
                initPubkeys(pubkeys)
                queueProfileRequests(pubkeys)
            }
        }

        NostrRepository.subscribe(
            followingListSubId,
            NostrFilter(kinds = listOf(3), authors = listOf(ownPubkey), limit = 1),
        )
    }

    private suspend fun loadFollowers() {
        collectorJobs += launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.events(followerSubId).collect { event ->
                if (event.kind != 3) return@collect
                val pubkey = event.pubkey
                if (knownPubkeys.add(pubkey)) {
                    profileMap[pubkey] = ProfileCache.get(pubkey)
                    schedulePublishEntries()
                    queueProfileRequests(listOf(pubkey))
                }
            }
        }

        // EOSE 後にローディング終了
        collectorJobs += launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.eose(followerSubId).collect {
                _state.update { it.copy(isLoading = false) }
            }
        }

        NostrRepository.subscribe(
            followerSubId,
            NostrFilter(kinds = listOf(3), pTags = listOf(ownPubkey), limit = 1000),
        )
    }

    private fun initPubkeys(pubkeys: List<String>) {
        val cachedProfiles = ProfileCache.getAll(pubkeys)
        pubkeys.forEach { pk ->
            knownPubkeys.add(pk)
            profileMap[pk] = cachedProfiles[pk]
        }
        schedulePublishEntries()
    }

    private fun queueProfileRequests(pubkeys: List<String>) {
        val newPubkeys = pubkeys.filter { pk ->
            pk in knownPubkeys && requestedProfilePubkeys.add(pk)
        }
        if (newPubkeys.isEmpty()) return

        queuedProfilePubkeys.addAll(newPubkeys)
        scheduleProfileBatch()
    }

    private fun scheduleProfileBatch() {
        if (profileBatchJob?.isActive == true) return

        profileBatchJob = launch {
            try {
                delay(PROFILE_BATCH_DELAY_MS)
                flushProfileBatches()
            } finally {
                profileBatchJob = null
                if (queuedProfilePubkeys.isNotEmpty()) {
                    scheduleProfileBatch()
                }
            }
        }
    }

    private suspend fun flushProfileBatches() {
        while (queuedProfilePubkeys.isNotEmpty()) {
            val batch = queuedProfilePubkeys.take(PROFILE_BATCH_SIZE)
            queuedProfilePubkeys.removeAll(batch.toSet())
            subscribeProfileBatch(batch)
        }
    }

    private suspend fun subscribeProfileBatch(pubkeys: List<String>) {
        val subId = "$profileSubId-${profileRequestSerial++}"
        activeProfileSubIds.add(subId)
        pendingProfileSubscriptions++

        val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.events(subId).collect { event ->
                if (event.kind != 0) return@collect
                if (event.pubkey in knownPubkeys) {
                    ProfileCache.putEvent(event)?.let { profile ->
                        profileMap[event.pubkey] = profile
                        schedulePublishEntries()
                    }
                }
            }
        }
        collectorJobs += eventJob

        // EOSE 後に購読解除 & ローディング終了
        collectorJobs += launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.eose(subId).first()
            NostrRepository.close(subId)
            activeProfileSubIds.remove(subId)
            pendingProfileSubscriptions--
            if (mode == FollowListMode.FOLLOWING && pendingProfileSubscriptions == 0) {
                _state.update { it.copy(isLoading = false) }
            }
        }

        NostrRepository.subscribe(
            subId,
            NostrFilter(kinds = listOf(0), authors = pubkeys, limit = pubkeys.size),
        )
    }

    private fun schedulePublishEntries() {
        if (publishJob?.isActive == true) return

        publishJob = launch {
            delay(PUBLISH_DELAY_MS)
            publishEntries()
        }
    }

    private fun publishEntries() {
        val entries = knownPubkeys
            .map { pk -> pk to profileMap[pk] }
        _state.update { it.copy(entries = entries) }
    }

    override fun onCleared() {
        super.onCleared()
        profileBatchJob?.cancel()
        publishJob?.cancel()
        collectorJobs.forEach { it.cancel() }
        NostrRepository.close(followerSubId)
        NostrRepository.close(followingListSubId)
        activeProfileSubIds.forEach { NostrRepository.close(it) }
        activeProfileSubIds.clear()
    }

    companion object {
        private const val PROFILE_BATCH_SIZE = 100
        private const val PROFILE_BATCH_DELAY_MS = 120L
        private const val PUBLISH_DELAY_MS = 180L
    }
}
