package com.nostr.torinos.ui.profile

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayListEventCache
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
    private val accountSession: AccountSession? = null,
) : SafeViewModel() {
    private val _state = MutableStateFlow(UserProfileState())
    val state: StateFlow<UserProfileState> = _state.asStateFlow()

    private val shortKey = pubkey.take(16)
    private val followingSubId = "uf-$shortKey"
    private val followersSubId = "ur-$shortKey"
    private val relayListSubId = "url-$shortKey"
    private val generalStatusSubId = "ugs-$shortKey"

    private val collectorJobs = mutableListOf<Job>()
    private var linkedProfileObserverJob: Job? = null
    private var followingCountStarted = false
    private var latestGeneralStatusCreatedAt = -1L
    private val linkedProfilePubkeys = linkedSetOf<String>()

    init {
        start()
        if (isWriteSupported) observeFollowState()
    }

    private fun observeFollowState() {
        collectorJobs += launch {
            combine(
                checkNotNull(accountSession).followRepository.followedPubkeys,
                accountSession.followRepository.loaded,
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
            checkNotNull(accountSession).followRepository.follow(pubkey)
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
            checkNotNull(accountSession).followRepository.unfollow(pubkey)
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
        RelayListEventCache.get(pubkey)?.let { cachedRelayList ->
            _state.update { it.copy(relayUrls = cachedRelayList.relayUrls()) }
        }
        ProfileRepository.getCached(pubkey)?.let { cachedProfile ->
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
            ProfileRepository.refresh(pubkey)
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
            ProfileRepository.observe(pubkey).collect { profile ->
                if (profile == null || profile == _state.value.profile) return@collect
                _state.update { it.copy(profile = profile) }
                scheduleLinkedProfileFetch(profile.about.orEmpty())
            }
        }

        collectorJobs += launch {
            NostrRepository.events(relayListSubId).collect { event ->
                if (event.kind != 10002) return@collect
                val latestEvent = RelayListEventCache.putEvent(event)
                _state.update { it.copy(relayUrls = latestEvent.relayUrls()) }
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
        linkedProfileObserverJob?.cancel()
        NostrRepository.close(followingSubId)
        NostrRepository.close(followersSubId)
        NostrRepository.close(relayListSubId)
        NostrRepository.close(generalStatusSubId)
    }

    private fun scheduleLinkedProfileFetch(text: String) {
        val discoveredPubkeys = extractNpubReferences(text)
            .map { it.pubkey }
            .distinct()
        if (discoveredPubkeys.isEmpty()) return
        linkedProfilePubkeys.addAll(discoveredPubkeys)
        val watchedPubkeys = linkedProfilePubkeys.toSet()
        val cachedProfiles = ProfileRepository.getCached(watchedPubkeys)
        if (cachedProfiles.isNotEmpty()) {
            _state.update { it.copy(linkedProfiles = it.linkedProfiles + cachedProfiles) }
        }
        linkedProfileObserverJob?.cancel()
        linkedProfileObserverJob = launch {
            ProfileRepository.observe(watchedPubkeys).collect { profiles ->
                _state.update { it.copy(linkedProfiles = it.linkedProfiles + profiles) }
            }
        }
        launch {
            ProfileRepository.ensureProfiles(
                pubkeys = watchedPubkeys,
                policy = ProfileFetchPolicy.CacheFirst(LINKED_PROFILE_MAX_AGE_MS),
                relayHint = deferredRelayUrl,
            )
        }
    }

    companion object {
        private const val FOLLOWERS_FETCH_LIMIT = 500
        private const val LINKED_PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
    }
}

private fun com.nostr.torinos.model.NostrEvent.relayUrls(): List<String> =
    tags.mapNotNull { tag ->
        tag.takeIf { it.size >= 2 && it[0] == "r" }
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.distinct()
