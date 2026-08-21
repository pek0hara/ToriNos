package com.nostr.torinos.ui.profile

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileCache
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
    val generalStatus: ProfileGeneralStatus? = null,
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
    private val generalStatusSubId = "ugs-$shortKey"

    private val collectorJobs = mutableListOf<Job>()
    private var followingCountStarted = false
    private var latestRelayListCreatedAt = -1L
    private var latestGeneralStatusCreatedAt = -1L
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
                    _state.update { current ->
                        if (current.isFollowLoading) {
                            current.copy(canFollow = true)
                        } else {
                            current.copy(isFollowing = follows.contains(pubkey), canFollow = true)
                        }
                    }
                } else {
                    _state.update { it.copy(isFollowing = null, canFollow = false) }
                }
            }
        }
    }

    fun follow() {
        if (_state.value.isFollowLoading || !_state.value.canFollow) return
        _state.update {
            it.copy(isFollowing = true, isFollowLoading = true, followError = null)
        }
        launch {
            FollowRepository.follow(pubkey)
                .onSuccess { _state.update { it.copy(isFollowLoading = false) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(isFollowing = false, isFollowLoading = false, followError = e.message)
                    }
                }
        }
    }

    fun unfollow() {
        if (_state.value.isFollowLoading || !_state.value.canFollow) return
        _state.update {
            it.copy(isFollowing = false, isFollowLoading = true, followError = null)
        }
        launch {
            FollowRepository.unfollow(pubkey)
                .onSuccess { _state.update { it.copy(isFollowLoading = false) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(isFollowing = true, isFollowLoading = false, followError = e.message)
                    }
                }
        }
    }

    fun clearFollowError() { _state.update { it.copy(followError = null) } }

    private fun start() {
        ProfileCache.get(pubkey)?.let { cachedProfile ->
            _state.update { it.copy(profile = cachedProfile) }
            scheduleLinkedProfileFetch(cachedProfile.about.orEmpty())
        }
        startCollectors()
        launch {
            NostrRepository.subscribe(
                relayListSubId,
                NostrFilter(kinds = listOf(10002), authors = listOf(pubkey), limit = 1),
            )
            NostrRepository.subscribe(
                generalStatusSubId,
                NostrFilter(
                    kinds = listOf(PROFILE_STATUS_KIND),
                    authors = listOf(pubkey),
                    dTags = listOf(PROFILE_GENERAL_STATUS_TAG),
                    limit = 1,
                ),
                relayUrl = deferredRelayUrl,
            )
        }
    }

    /** 画面を開くたびに最新プロフィールを取得し、受信時にキャッシュを更新する。 */
    fun refreshProfile() {
        launch {
            NostrRepository.close(profileSubId)
            NostrRepository.subscribe(
                profileSubId,
                NostrFilter(kinds = listOf(0), authors = listOf(pubkey), limit = 1),
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
            ProfileCache.observe(pubkey).collect { profile ->
                if (profile == null || profile == _state.value.profile) return@collect
                _state.update { it.copy(profile = profile) }
                scheduleLinkedProfileFetch(profile.about.orEmpty())
            }
        }

        collectorJobs += launch {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = ProfileCache.putEvent(event) ?: return@collect
                _state.update { it.copy(profile = profile) }
                scheduleLinkedProfileFetch(profile.about.orEmpty())
            }
        }

        collectorJobs += launch {
            NostrRepository.events(linkedProfileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = ProfileCache.putEvent(event) ?: return@collect
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
            NostrRepository.events(generalStatusSubId).collect { event ->
                if (event.createdAt <= latestGeneralStatusCreatedAt) return@collect
                latestGeneralStatusCreatedAt = event.createdAt
                _state.update { it.copy(generalStatus = event.toActiveGeneralStatus()) }
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
        NostrRepository.close(generalStatusSubId)
    }

    private fun scheduleLinkedProfileFetch(text: String) {
        val linkedPubkeys = extractNpubReferences(text)
            .map { it.pubkey }
            .filter { it !in _state.value.linkedProfiles }
            .distinct()
        val cachedProfiles = ProfileCache.getAll(linkedPubkeys)
        if (cachedProfiles.isNotEmpty()) {
            _state.update { it.copy(linkedProfiles = it.linkedProfiles + cachedProfiles) }
        }
        val authors = linkedPubkeys
            .filterNot { it in cachedProfiles }
            .filter { pendingLinkedProfilePubkeys.add(it) }
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
