package com.nostr.torinos.ui.profile

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayListEventCache
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.status.GENERAL_STATUS_IDENTIFIER
import com.nostr.torinos.status.PublishStatusCommand
import com.nostr.torinos.status.StatusPublishResult
import com.nostr.torinos.status.StatusPublishState
import com.nostr.torinos.status.StatusPublishTarget
import com.nostr.torinos.status.StatusPublisher
import com.nostr.torinos.status.StatusSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MyProfileState(
    val profile: NostrProfile? = null,
    val linkedProfiles: Map<String, NostrProfile> = emptyMap(),
    val relayUrls: List<String> = emptyList(),
    val generalStatus: ProfileGeneralStatus? = null,
    val followingCount: Int = 0,
    /** 数えている最中に届いたフォロワー数。 */
    val followersCount: Int = 0,
    val isFollowersLoading: Boolean = false,
    /** 前に数えて保存したフォロワー数。null = まだ数えたことがない。 */
    val storedFollowerCount: FollowerCountSnapshot? = null,
    val generalStatusPublishState: StatusPublishState = StatusPublishState.Idle,
)

class MyProfileViewModel(
    private val ownPubkey: String,
    private val accountSession: AccountSession? = null,
) : SafeViewModel() {
    private val _state = MutableStateFlow(MyProfileState())
    val state: StateFlow<MyProfileState> = _state.asStateFlow()

    private val subIdKey = ownPubkey.take(16)
    private val relayListSubId = "mp-relay-list-$subIdKey"
    private val generalStatusSubId = "mp-general-status-$subIdKey"

    private val collectorJobs = mutableListOf<Job>()
    private var linkedProfileObserverJob: Job? = null
    private var generalStatusSnapshot = StatusSnapshot()
    private var hasPublishedRelayList = false
    private val linkedProfilePubkeys = linkedSetOf<String>()
    private val statusPublisher = StatusPublisher(
        signer = accountSession?.signer,
        customEmojis = { accountSession?.customEmojis?.preferences?.value?.available.orEmpty() },
        setAddressOf = { accountSession?.customEmojis?.preferences?.value?.setAddressOf(it) },
    )

    private val followerCountStore = FollowerCountStore.shared
    private val followerCountLoader = FollowerCountLoader(
        pubkey = ownPubkey,
        subId = "mp-followers-$subIdKey",
        launch = { block -> launch(block = block) },
        store = followerCountStore,
    )

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
            followerCountLoader.state.collect { load ->
                _state.update { it.copy(followersCount = load.liveCount, isFollowersLoading = load.isLoading) }
            }
        }

        collectorJobs += launch {
            followerCountStore.counts.collect { counts ->
                val stored = counts[ownPubkey]
                if (stored != _state.value.storedFollowerCount) {
                    _state.update { it.copy(storedFollowerCount = stored) }
                }
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
                applyGeneralStatusEvent(event)
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

    /** 押されたときだけ数える。保存済みの数があればそれを出したまま数え直す。 */
    fun fetchFollowers() {
        followerCountLoader.start()
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

    fun publishStatus(
        statusTag: String,
        content: String,
        expiration: Long?,
        referenceUrl: String?,
    ) {
        publishStatusCommand(
            PublishStatusCommand(statusTag, content, expiration, referenceUrl),
        )
    }

    fun deleteGeneralStatus() {
        publishStatusCommand(
            PublishStatusCommand(
                identifier = GENERAL_STATUS_IDENTIFIER,
                content = "",
                expiration = null,
                referenceUrl = null,
                isDeletion = true,
            ),
        )
    }

    private fun publishStatusCommand(command: PublishStatusCommand) {
        if (_state.value.generalStatusPublishState is StatusPublishState.Publishing) return
        launch {
            _state.update { it.copy(generalStatusPublishState = StatusPublishState.Publishing) }
            when (val result = statusPublisher.publish(command, StatusPublishTarget.WritableRelays)) {
                is StatusPublishResult.Published -> {
                    val isGeneral = command.identifier.trim().ifBlank { GENERAL_STATUS_IDENTIFIER } ==
                        GENERAL_STATUS_IDENTIFIER
                    if (isGeneral) applyGeneralStatusEvent(result.event)
                    _state.update {
                        it.copy(
                            generalStatusPublishState = StatusPublishState.Succeeded(result.event.id),
                        )
                    }
                }
                is StatusPublishResult.Rejected -> {
                    _state.update {
                        it.copy(generalStatusPublishState = StatusPublishState.Failed(result.message))
                    }
                }
            }
        }
    }

    private fun applyGeneralStatusEvent(event: NostrEvent) {
        val reduction = reduceProfileGeneralStatus(
            snapshot = generalStatusSnapshot,
            event = event,
            expectedPubkey = ownPubkey,
        ) ?: return
        generalStatusSnapshot = reduction.snapshot
        _state.update { it.copy(generalStatus = reduction.generalStatus) }
    }

    fun clearGeneralStatusError() {
        _state.update {
            if (it.generalStatusPublishState is StatusPublishState.Failed) {
                it.copy(generalStatusPublishState = StatusPublishState.Idle)
            } else {
                it
            }
        }
    }

    fun consumeGeneralStatusPublishResult(eventId: String) {
        _state.update {
            val succeeded = it.generalStatusPublishState as? StatusPublishState.Succeeded
            if (succeeded?.eventId == eventId) {
                it.copy(generalStatusPublishState = StatusPublishState.Idle)
            } else {
                it
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        collectorJobs.forEach { it.cancel() }
        followerCountLoader.close()
        linkedProfileObserverJob?.cancel()
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
