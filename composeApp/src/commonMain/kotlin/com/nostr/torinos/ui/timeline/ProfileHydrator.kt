package com.nostr.torinos.ui.timeline

import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

internal data class ProfilePatch(val profiles: Map<String, NostrProfile>)

/** ProfileRepositoryの監視と不足プロフィール取得を画面Stateから分離する。 */
internal class ProfileHydrator(
    private val scope: CoroutineScope,
    private val policy: ProfileFetchPolicy,
) {
    private val requested = linkedSetOf<String>()
    private val mutableUpdates = MutableSharedFlow<ProfilePatch>(extraBufferCapacity = 16)
    val updates: Flow<ProfilePatch> = mutableUpdates.asSharedFlow()
    private val observation: Job = scope.launch {
        ProfileRepository.observeAll().collect { cached ->
            val relevant = cached.filterKeys { it in requested }
            if (relevant.isNotEmpty()) mutableUpdates.emit(ProfilePatch(relevant))
        }
    }

    fun request(pubkeys: Set<String>) {
        val normalized = pubkeys.filterTo(linkedSetOf()) { it.isNotBlank() }
        if (normalized.isEmpty()) return
        requested += normalized
        val cached = normalized.mapNotNull { key ->
            ProfileRepository.getCached(key)?.let { key to it }
        }.toMap()
        if (cached.isNotEmpty()) mutableUpdates.tryEmit(ProfilePatch(cached))
        scope.launch { ProfileRepository.ensureProfiles(normalized, policy) }
    }

    fun requestMentioned(content: String) {
        request(extractNpubReferences(content).mapTo(linkedSetOf()) { it.pubkey })
    }

    fun close() {
        observation.cancel()
        requested.clear()
    }
}
