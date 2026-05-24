package com.nostr.torinos.ui.profile

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.network.NostrRepository
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
    val generalStatus: String? = null,
    val followingCount: Int = 0,
    val followersCount: Int = 0,
    val isFollowersLoading: Boolean = false,
    val followersLoaded: Boolean = false,
    val isGeneralStatusPublishing: Boolean = false,
    val generalStatusPublishCompletedCount: Int = 0,
    val generalStatusError: String? = null,
)

class MyProfileViewModel(private val ownPubkey: String) : SafeViewModel() {
    private val _state = MutableStateFlow(MyProfileState())
    val state: StateFlow<MyProfileState> = _state.asStateFlow()

    private val subIdKey = ownPubkey.take(16)
    private val profileSubId = "mp-profile-$subIdKey"
    private val linkedProfileSubId = "mp-linked-profile-$subIdKey"
    private val followerSubId = "mp-followers-$subIdKey"
    private val relayListSubId = "mp-relay-list-$subIdKey"
    private val generalStatusSubId = "mp-general-status-$subIdKey"

    private val collectorJobs = mutableListOf<Job>()
    private var followerCollectorJob: Job? = null
    private var followerEoseJob: Job? = null
    private var latestRelayListCreatedAt = -1L
    private var latestGeneralStatusCreatedAt = -1L
    private var hasPublishedRelayList = false
    private val pendingLinkedProfilePubkeys = linkedSetOf<String>()

    init {
        start()
    }

    private fun start() {
        collectorJobs += launch {
            FollowRepository.followedPubkeys.collect { follows ->
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
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                event.toProfile()?.let { profile ->
                    _state.update { it.copy(profile = profile) }
                    scheduleLinkedProfileFetch(profile.about.orEmpty())
                }
            }
        }

        collectorJobs += launch {
            NostrRepository.events(linkedProfileSubId).collect { event ->
                if (event.kind != 0) return@collect
                event.toProfile()?.let { profile ->
                    pendingLinkedProfilePubkeys.remove(event.pubkey)
                    _state.update {
                        it.copy(linkedProfiles = it.linkedProfiles + (event.pubkey to profile))
                    }
                }
            }
        }

        collectorJobs += launch {
            NostrRepository.events(relayListSubId).collect { event ->
                if (event.kind != 10002 || event.createdAt <= latestRelayListCreatedAt) return@collect
                latestRelayListCreatedAt = event.createdAt
                val relayUrls = event.relayUrls()
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
                _state.update { it.copy(generalStatus = event.toActiveGeneralStatusContent()) }
            }
        }

        launch {
            NostrRepository.subscribe(
                profileSubId,
                NostrFilter(kinds = listOf(0), authors = listOf(ownPubkey), limit = 1),
            )
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
        _state.update { it.copy(profile = profile) }
        launch {
            delay(2_000)
            NostrRepository.close(profileSubId)
            NostrRepository.subscribe(
                profileSubId,
                NostrFilter(kinds = listOf(0), authors = listOf(ownPubkey), limit = 1),
            )
        }
    }

    fun publishGeneralStatus(content: String) {
        val body = content.trim()
        if (body.isEmpty()) {
            _state.update { it.copy(generalStatusError = "ステータスを入力してください") }
            return
        }
        launch {
            _state.update { it.copy(isGeneralStatusPublishing = true, generalStatusError = null) }
            try {
                val privateKeyHex = KeyStorage.loadPrivateKey()
                    ?: error("秘密鍵が見つかりません")
                val event = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = body,
                    kind = PROFILE_STATUS_KIND,
                    tags = listOf(listOf("d", PROFILE_GENERAL_STATUS_TAG)),
                )
                latestGeneralStatusCreatedAt = event.createdAt
                _state.update {
                    it.copy(
                        generalStatus = body,
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
        NostrRepository.close(profileSubId)
        NostrRepository.close(linkedProfileSubId)
        NostrRepository.close(followerSubId)
        NostrRepository.close(relayListSubId)
        NostrRepository.close(generalStatusSubId)
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
            )
        }
    }
}

private fun com.nostr.torinos.model.NostrEvent.relayUrls(): List<String> =
    tags.mapNotNull { tag ->
        tag.takeIf { it.size >= 2 && it[0] == "r" }
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.distinct()
