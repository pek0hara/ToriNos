package com.nostr.torinos.ui.profile

import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    private val followerSubId = "fl-followers-${ownPubkey.take(8)}"
    private val followingListSubId = "fl-kind3-${ownPubkey.take(16)}"
    private val collectorJobs = mutableListOf<Job>()
    private val queuedProfilePubkeys = linkedSetOf<String>()
    private val requestedProfilePubkeys = mutableSetOf<String>()

    // pubkey → profile
    private val profileMap = mutableMapOf<String, NostrProfile?>()
    // 取得済みpubkeyセット
    private val knownPubkeys = linkedSetOf<String>()
    private var pendingProfileSubscriptions = 0
    private var profileBatchJob: Job? = null
    private var publishJob: Job? = null

    init {
        collectorJobs += launch {
            ProfileRepository.observeAll().collect { cachedProfiles ->
                var changed = false
                knownPubkeys.forEach { pubkey ->
                    val profile = cachedProfiles[pubkey]
                    if (profile != null && profileMap[pubkey] != profile) {
                        profileMap[pubkey] = profile
                        changed = true
                    }
                }
                if (changed) schedulePublishEntries()
            }
        }
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
        val targetRelayUrls = NostrRepository.targetRelayUrls(RelayTarget.AllEnabled)
        val completedRelayUrls = mutableSetOf<String>()
        val allRelaysComplete = CompletableDeferred<Unit>()

        collectorJobs += launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.events(followingListSubId).collect { event ->
                if (event.kind != 3) return@collect
                if (event.createdAt > latestAt) {
                    latestAt = event.createdAt
                    latestPubkeys = event.tags
                        .filter { it.size >= 2 && it[0] == "p" }
                        .map { it[1] }
                        .distinct()
                    replaceFollowingPubkeys(latestPubkeys)
                }
            }
        }

        val eoseJob = launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.eoseRelays(followingListSubId).collect { relayUrl ->
                completedRelayUrls.add(relayUrl)
                if (hasCompletedAllFollowRelays(targetRelayUrls, completedRelayUrls)) {
                    allRelaysComplete.complete(Unit)
                }
            }
        }
        collectorJobs += eoseJob

        try {
            NostrRepository.subscribe(
                followingListSubId,
                NostrFilter(kinds = listOf(3), authors = listOf(ownPubkey), limit = 1),
            )
            withTimeoutOrNull(FOLLOWING_LIST_TIMEOUT_MS) {
                allRelaysComplete.await()
            }
        } finally {
            eoseJob.cancel()
            NostrRepository.close(followingListSubId)
        }

        if (latestAt < 0L || latestPubkeys.isEmpty()) {
            _state.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun loadFollowers() {
        collectorJobs += launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.events(followerSubId).collect { event ->
                if (event.kind != 3) return@collect
                val pubkey = event.pubkey
                if (knownPubkeys.add(pubkey)) {
                    profileMap[pubkey] = ProfileRepository.getCached(pubkey)
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
        val cachedProfiles = ProfileRepository.getCached(pubkeys)
        pubkeys.forEach { pk ->
            knownPubkeys.add(pk)
            if (pk !in profileMap) {
                profileMap[pk] = cachedProfiles[pk]
            }
        }
        schedulePublishEntries()
    }

    private fun replaceFollowingPubkeys(pubkeys: List<String>) {
        val pubkeySet = pubkeys.toSet()
        profileMap.keys.retainAll(pubkeySet)
        queuedProfilePubkeys.retainAll(pubkeySet)
        requestedProfilePubkeys.retainAll(pubkeySet)

        knownPubkeys.clear()
        initPubkeys(pubkeys)
        queueProfileRequests(pubkeys)

        if (pubkeys.isEmpty()) {
            _state.update { it.copy(isLoading = false) }
        }
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
        pendingProfileSubscriptions++
        try {
            ProfileRepository.ensureProfiles(
                pubkeys.toSet(),
                policy = ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS),
            )
        } finally {
            pendingProfileSubscriptions--
            if (mode == FollowListMode.FOLLOWING && pendingProfileSubscriptions == 0) {
                _state.update { it.copy(isLoading = false) }
            }
        }
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
    }

    companion object {
        private const val PROFILE_BATCH_SIZE = 100
        private const val PROFILE_BATCH_DELAY_MS = 120L
        private const val PUBLISH_DELAY_MS = 180L
        private const val FOLLOWING_LIST_TIMEOUT_MS = 10_000L
        private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
    }
}

internal fun hasCompletedAllFollowRelays(
    targetRelayUrls: Set<String>,
    completedRelayUrls: Set<String>,
): Boolean = targetRelayUrls.isEmpty() || completedRelayUrls.containsAll(targetRelayUrls)
