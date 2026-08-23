package com.nostr.torinos.ui.profile

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayListEventCache
import com.nostr.torinos.network.RelayStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

data class MyProfileState(
    val profile: NostrProfile? = null,
    val linkedProfiles: Map<String, NostrProfile> = emptyMap(),
    val relayUrls: List<String> = emptyList(),
    val generalStatus: ProfileGeneralStatus? = null,
    val followingCount: Int = 0,
    val followersCount: Int = 0,
    val isFollowersLoading: Boolean = false,
    val followersLoaded: Boolean = false,
    val isGeneralStatusPublishing: Boolean = false,
    val generalStatusPublishCompletedCount: Int = 0,
    val generalStatusError: String? = null,
)

class MyProfileViewModel(
    private val ownPubkey: String,
    private val accountSession: AccountSession? = null,
) : SafeViewModel() {
    private val _state = MutableStateFlow(MyProfileState())
    val state: StateFlow<MyProfileState> = _state.asStateFlow()

    private val subIdKey = ownPubkey.take(16)
    private val followerSubId = "mp-followers-$subIdKey"
    private val relayListSubId = "mp-relay-list-$subIdKey"
    private val generalStatusSubId = "mp-general-status-$subIdKey"

    private val collectorJobs = mutableListOf<Job>()
    private var followerCollectorJob: Job? = null
    private var followerEoseJob: Job? = null
    private var linkedProfileObserverJob: Job? = null
    private var latestGeneralStatusCreatedAt = -1L
    private var hasPublishedRelayList = false
    private val linkedProfilePubkeys = linkedSetOf<String>()

    init {
        start()
    }

    private fun start() {
        RelayListEventCache.get(ownPubkey)?.let { cachedRelayList ->
            val relayUrls = cachedRelayList.relayUrls()
            if (relayUrls.isNotEmpty()) {
                hasPublishedRelayList = true
                _state.update { it.copy(relayUrls = relayUrls) }
            }
        }
        ProfileRepository.getCached(ownPubkey)?.let { cachedProfile ->
            _state.update { it.copy(profile = cachedProfile) }
            scheduleLinkedProfileFetch(cachedProfile.about.orEmpty())
        }
        collectorJobs += launch {
            accountSession?.followRepository?.followedPubkeys?.collect { follows ->
                _state.update { it.copy(followingCount = follows.size) }
            }
        }

        collectorJobs += launch {
            RelayStore.relays.collect { relayUrls ->
                if (!hasPublishedRelayList) {
                    _state.update { it.copy(relayUrls = relayUrls) }
                }
            }
        }

        collectorJobs += launch {
            ProfileRepository.observe(ownPubkey).collect { profile ->
                profile ?: return@collect
                if (_state.value.profile == profile) return@collect
                _state.update { it.copy(profile = profile) }
                scheduleLinkedProfileFetch(profile.about.orEmpty())
            }
        }

        collectorJobs += launch {
            NostrRepository.events(relayListSubId).collect { event ->
                if (event.kind != 10002) return@collect
                val relayUrls = RelayListEventCache.putEvent(event).relayUrls()
                if (relayUrls.isNotEmpty()) {
                    hasPublishedRelayList = true
                    _state.update { it.copy(relayUrls = relayUrls) }
                }
            }
        }

        collectorJobs += launch {
            NostrRepository.events(generalStatusSubId).collect { event ->
                if (event.createdAt <= latestGeneralStatusCreatedAt) return@collect
                latestGeneralStatusCreatedAt = event.createdAt
                _state.update { it.copy(generalStatus = event.toActiveGeneralStatus()) }
            }
        }

        launch {
            NostrRepository.subscribe(
                relayListSubId,
                NostrFilter(kinds = listOf(10002), authors = listOf(ownPubkey), limit = 1),
            )
            NostrRepository.subscribe(
                generalStatusSubId,
                NostrFilter(
                    kinds = listOf(PROFILE_STATUS_KIND),
                    authors = listOf(ownPubkey),
                    dTags = listOf(PROFILE_GENERAL_STATUS_TAG),
                    limit = 1,
                ),
            )
        }
    }

    /** 画面を開くたびに最新プロフィールを取得し、受信時にキャッシュを更新する。 */
    fun refreshProfile() {
        launch {
            ProfileRepository.refresh(ownPubkey)
        }
    }

    fun fetchFollowers() {
        if (_state.value.isFollowersLoading) return
        _state.update { it.copy(isFollowersLoading = true, followersCount = 0) }
        followerCollectorJob?.cancel()
        followerEoseJob?.cancel()
        val followerPubkeys = linkedSetOf<String>()
        followerCollectorJob = launch {
            NostrRepository.events(followerSubId).collect { event ->
                if (event.kind != 3) return@collect
                if (followerPubkeys.add(event.pubkey)) {
                    _state.update { it.copy(followersCount = followerPubkeys.size) }
                }
            }
        }
        followerEoseJob = launch {
            withTimeoutOrNull(10_000) {
                NostrRepository.eose(followerSubId).first()
            }
            _state.update { it.copy(isFollowersLoading = false, followersLoaded = true) }
        }
        launch {
            NostrRepository.close(followerSubId)
            NostrRepository.subscribe(
                followerSubId,
                NostrFilter(kinds = listOf(3), pTags = listOf(ownPubkey), limit = 1000),
            )
        }
    }

    /** 編集保存後に UI を即時更新する（リレーの応答を待たない）。 */
    fun applyProfile(profile: NostrProfile) {
        ProfileRepository.applyOptimistic(ownPubkey, profile)
        _state.update { it.copy(profile = profile) }
        launch {
            delay(2_000)
            refreshProfile()
        }
    }

    fun publishGeneralStatus(content: String) {
        val body = content.trim()
        launch {
            _state.update { it.copy(isGeneralStatusPublishing = true, generalStatusError = null) }
            try {
                val signer = accountSession?.signer ?: error("秘密鍵が見つかりません")
                val event = signer.sign(
                    content = body,
                    kind = PROFILE_STATUS_KIND,
                    tags = listOf(listOf("d", PROFILE_GENERAL_STATUS_TAG)) +
                        customEmojiTagsForContent(body, CustomEmojiStore.emojis.value),
                )
                latestGeneralStatusCreatedAt = event.createdAt
                _state.update {
                    it.copy(
                        generalStatus = body.takeIf { it.isNotBlank() }?.let {
                            ProfileGeneralStatus(
                                content = it,
                                customEmojis = event.tags.customEmojiMap(),
                            )
                        },
                        isGeneralStatusPublishing = false,
                        generalStatusPublishCompletedCount = it.generalStatusPublishCompletedCount + 1,
                    )
                }
                NostrRepository.publish(event)
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        isGeneralStatusPublishing = false,
                        generalStatusError = e.message ?: "ステータスの保存に失敗しました",
                    )
                }
            }
        }
    }

    fun clearGeneralStatusError() {
        _state.update { it.copy(generalStatusError = null) }
    }

    override fun onCleared() {
        super.onCleared()
        collectorJobs.forEach { it.cancel() }
        followerCollectorJob?.cancel()
        followerEoseJob?.cancel()
        linkedProfileObserverJob?.cancel()
        NostrRepository.close(followerSubId)
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
            )
        }
    }

    companion object {
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
