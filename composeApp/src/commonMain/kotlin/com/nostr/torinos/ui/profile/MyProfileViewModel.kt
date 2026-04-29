package com.nostr.torinos.ui.profile

import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.hexToNsec
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.util.logException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MyProfileState(
    val profile: NostrProfile? = null,
    val followingCount: Int = 0,
    val followersCount: Int = 0,
    val isSecretKeyVisible: Boolean = false,
    val keyError: String? = null,
)

class MyProfileViewModel(private val ownPubkey: String) : SafeViewModel() {
    private val _state = MutableStateFlow(MyProfileState())
    val state: StateFlow<MyProfileState> = _state.asStateFlow()

    private val _secretKeyEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val secretKeyEvent: SharedFlow<String> = _secretKeyEvent.asSharedFlow()

    private val profileSubId = "mp-profile"
    private val followerSubId = "mp-followers"

    private val collectorJobs = mutableListOf<Job>()

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
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                event.toProfile()?.let { profile ->
                    _state.update { it.copy(profile = profile) }
                }
            }
        }

        val followerPubkeys = linkedSetOf<String>()
        collectorJobs += launch {
            NostrRepository.events(followerSubId).collect { event ->
                if (event.kind != 3) return@collect
                if (followerPubkeys.add(event.pubkey)) {
                    _state.update { it.copy(followersCount = followerPubkeys.size) }
                }
            }
        }

        launch {
            NostrRepository.subscribe(
                profileSubId,
                NostrFilter(kinds = listOf(0), authors = listOf(ownPubkey), limit = 1),
            )
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

    fun showSecretKey() {
        launch {
            val nsec = runCatching {
                val privateKey = KeyStorage.loadPrivateKey()
                    ?: error("秘密鍵が保存されていません")
                hexToNsec(privateKey)
            }.getOrElse { e ->
                logException("MyProfileViewModel", e, "Failed to load private key for display")
                _state.update {
                    it.copy(
                        isSecretKeyVisible = false,
                        keyError = e.message ?: "秘密鍵を読み込めませんでした",
                    )
                }
                return@launch
            }

            _state.update { it.copy(isSecretKeyVisible = true, keyError = null) }
            _secretKeyEvent.emit(nsec)
        }
    }

    fun hideSecretKey() {
        _state.update { it.copy(isSecretKeyVisible = false, keyError = null) }
    }

    override fun onCleared() {
        super.onCleared()
        collectorJobs.forEach { it.cancel() }
        NostrRepository.close(profileSubId)
        NostrRepository.close(followerSubId)
    }
}
