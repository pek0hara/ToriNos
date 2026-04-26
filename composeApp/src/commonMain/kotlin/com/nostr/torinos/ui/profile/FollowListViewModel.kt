package com.nostr.torinos.ui.profile

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.network.NostrRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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

    private val profileSubId = "fl-profiles-${mode.name}"
    private val followerSubId = "fl-followers"
    private val collectorJobs = mutableListOf<Job>()

    // pubkey → profile
    private val profileMap = mutableMapOf<String, NostrProfile?>()
    // 取得済みpubkeyセット
    private val knownPubkeys = linkedSetOf<String>()

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
        // FollowRepositoryのロード完了を待つ
        FollowRepository.loaded.first { it }
        val pubkeys = FollowRepository.followedPubkeys.value.toList()
        if (pubkeys.isEmpty()) {
            _state.update { it.copy(isLoading = false) }
            return
        }
        initPubkeys(pubkeys)
        subscribeProfiles(pubkeys)
    }

    private suspend fun loadFollowers() {
        // 自分のpubkeyをpタグに持つkind:3を取得
        NostrRepository.subscribe(
            followerSubId,
            NostrFilter(kinds = listOf(3), pTags = listOf(ownPubkey), limit = 1000),
        )

        collectorJobs += launch {
            NostrRepository.events(followerSubId).collect { event ->
                if (event.kind != 3) return@collect
                val pubkey = event.pubkey
                if (knownPubkeys.add(pubkey)) {
                    profileMap[pubkey] = null
                    publishEntries()
                    // プロフィールをリクエスト
                    subscribeProfiles(listOf(pubkey))
                }
            }
        }

        // EOSE 後にローディング終了
        collectorJobs += launch {
            NostrRepository.eose(followerSubId).collect {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun initPubkeys(pubkeys: List<String>) {
        pubkeys.forEach { pk ->
            knownPubkeys.add(pk)
            profileMap[pk] = null
        }
        publishEntries()
    }

    private fun subscribeProfiles(pubkeys: List<String>) {
        if (pubkeys.isEmpty()) return
        val subId = "$profileSubId-${pubkeys.first().take(8)}"
        launch {
            NostrRepository.subscribe(
                subId,
                NostrFilter(kinds = listOf(0), authors = pubkeys, limit = pubkeys.size),
            )
        }
        collectorJobs += launch {
            NostrRepository.events(subId).collect { event ->
                if (event.kind != 0) return@collect
                if (event.pubkey in knownPubkeys) {
                    profileMap[event.pubkey] = event.toProfile()
                    publishEntries()
                }
            }
        }
        // EOSE 後に購読解除 & ローディング終了
        launch {
            NostrRepository.eose(subId).collect {
                NostrRepository.close(subId)
                if (mode == FollowListMode.FOLLOWING) {
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun publishEntries() {
        val sorted = knownPubkeys
            .map { pk -> pk to profileMap[pk] }
            .sortedBy { (_, profile) -> profile?.bestName ?: "\uFFFF" }
        _state.update { it.copy(entries = sorted) }
    }

    override fun onCleared() {
        super.onCleared()
        collectorJobs.forEach { it.cancel() }
        NostrRepository.close(followerSubId)
    }
}
