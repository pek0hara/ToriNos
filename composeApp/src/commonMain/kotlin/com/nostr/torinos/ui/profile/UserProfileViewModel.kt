package com.nostr.torinos.ui.profile

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.network.NostrRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UserProfileState(
    val profile: NostrProfile? = null,
    val linkedProfiles: Map<String, NostrProfile> = emptyMap(),
    val relayUrls: List<String> = emptyList(),
    /** null = フォローリスト未ロード */
    val isFollowing: Boolean? = null,
    val isFollowLoading: Boolean = false,
    val followError: String? = null,
    val canFollow: Boolean = false,
    val followingCount: Int = 0,
    val followersCount: Int = 0,
    val isFollowersCountLimited: Boolean = false,
    val isFollowersLoading: Boolean = false,
    val followersLoaded: Boolean = false,
)

class UserProfileViewModel(
    private val pubkey: String,
    private val deferredRelayUrl: String? = null,
) : SafeViewModel() {
    private val _state = MutableStateFlow(UserProfileState())
    val state: StateFlow<UserProfileState> = _state.asStateFlow()

    private val shortKey = pubkey.take(16)
    private val profileSubId = "up-$shortKey"
    private val linkedProfileSubId = "upl-$shortKey"
    private val followingSubId = "uf-$shortKey"
    private val followersSubId = "ur-$shortKey"
    private val relayListSubId = "url-$shortKey"

    private val collectorJobs = mutableListOf<Job>()
    private var followingCountStarted = false
    private var latestRelayListCreatedAt = -1L
    private val pendingLinkedProfilePubkeys = linkedSetOf<String>()

    init {
        start()
        if (isWriteSupported) observeFollowState()
    }

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

    private fun start() {
        startCollectors()
        launch {
            NostrRepository.subscribe(
                profileSubId,
                NostrFilter(kinds = listOf(0), authors = listOf(pubkey), limit = 1),
            )
            NostrRepository.subscribe(
                relayListSubId,
                NostrFilter(kinds = listOf(10002), authors = listOf(pubkey), limit = 1),
            )
        }
    }

    fun loadFollowingCount() {
        if (followingCountStarted) return
        followingCountStarted = true
        launch {
            NostrRepository.subscribe(
                followingSubId,
                NostrFilter(kinds = listOf(3), authors = listOf(pubkey), limit = 1),
                relayUrl = deferredRelayUrl,
            )
        }
    }

    fun loadFollowersCount() {
        if (_state.value.isFollowersLoading) return
        _state.update { it.copy(isFollowersLoading = true) }
        launch {
            NostrRepository.subscribe(
                followersSubId,
                NostrFilter(kinds = listOf(3), pTags = listOf(pubkey), limit = FOLLOWERS_FETCH_LIMIT),
                relayUrl = deferredRelayUrl,
            )
        }
    }

    private fun startCollectors() {
        collectorJobs += launch {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                _state.update { it.copy(profile = profile) }
                scheduleLinkedProfileFetch(profile.about.orEmpty())
            }
        }

        collectorJobs += launch {
            NostrRepository.events(linkedProfileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                pendingLinkedProfilePubkeys.remove(event.pubkey)
                _state.update {
                    it.copy(linkedProfiles = it.linkedProfiles + (event.pubkey to profile))
                }
            }
        }

        collectorJobs += launch {
            NostrRepository.events(relayListSubId).collect { event ->
                if (event.kind != 10002 || event.createdAt <= latestRelayListCreatedAt) return@collect
                latestRelayListCreatedAt = event.createdAt
                _state.update { it.copy(relayUrls = event.relayUrls()) }
            }
        }

        collectorJobs += launch {
            var latestAt = -1L
            NostrRepository.events(followingSubId).collect { event ->
                if (event.kind != 3) return@collect
                if (event.createdAt > latestAt) {
                    latestAt = event.createdAt
                    val count = event.tags
                        .mapNotNull { tag -> tag.takeIf { it.size >= 2 && it[0] == "p" }?.get(1) }
                        .distinct()
                        .size
                    _state.update { it.copy(followingCount = count) }
                }
            }
        }

        val followerPubkeys = linkedSetOf<String>()
        val followerEventIds = linkedSetOf<String>()
        var receivedFollowerEvents = 0
        collectorJobs += launch {
            NostrRepository.events(followersSubId).collect { event ->
                if (event.kind != 3) return@collect
                if (!followerEventIds.add(event.id)) return@collect
                receivedFollowerEvents++
                if (followerPubkeys.add(event.pubkey)) {
                    _state.update { it.copy(followersCount = followerPubkeys.size) }
                }
            }
        }

        collectorJobs += launch {
            NostrRepository.eose(followersSubId).collect {
                _state.update {
                    it.copy(
                        isFollowersLoading = false,
                        followersLoaded = true,
                        isFollowersCountLimited = receivedFollowerEvents >= FOLLOWERS_FETCH_LIMIT,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        collectorJobs.forEach { it.cancel() }
        NostrRepository.close(profileSubId)
        NostrRepository.close(linkedProfileSubId)
        NostrRepository.close(followingSubId)
        NostrRepository.close(followersSubId)
        NostrRepository.close(relayListSubId)
    }

    private fun scheduleLinkedProfileFetch(text: String) {
        val authors = extractNpubReferences(text)
            .map { it.pubkey }
            .filter { it !in _state.value.linkedProfiles && pendingLinkedProfilePubkeys.add(it) }
        if (authors.isEmpty()) return
        launch {
            NostrRepository.subscribe(
                linkedProfileSubId,
                NostrFilter(kinds = listOf(0), authors = pendingLinkedProfilePubkeys.toList()),
                relayUrl = deferredRelayUrl,
            )
        }
    }

    companion object {
        private const val FOLLOWERS_FETCH_LIMIT = 500
    }
}

private fun com.nostr.torinos.model.NostrEvent.relayUrls(): List<String> =
    tags.mapNotNull { tag ->
        tag.takeIf { it.size >= 2 && it[0] == "r" }
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.distinct()
