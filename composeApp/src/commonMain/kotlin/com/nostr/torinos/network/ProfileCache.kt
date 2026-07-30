package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

object ProfileCache {
    data class Entry(
        val profile: NostrProfile,
        val createdAt: Long,
    )

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    private val profileUpdates = MutableSharedFlow<Pair<String, NostrProfile>>(
        extraBufferCapacity = 64,
    )

    fun put(pubkey: String, profile: NostrProfile, createdAt: Long = Long.MIN_VALUE) {
        entries.update { current ->
            val existing = current[pubkey]
            if (existing != null && existing.createdAt > createdAt) {
                current
            } else {
                current + (pubkey to Entry(profile, createdAt))
            }
        }
        get(pubkey)?.let { currentProfile ->
            profileUpdates.tryEmit(pubkey to currentProfile)
        }
    }

    fun putAll(profiles: Map<String, NostrProfile>, createdAt: Long = Long.MIN_VALUE) {
        if (profiles.isEmpty()) return
        entries.update { current ->
            profiles.entries.fold(current) { acc, (pubkey, profile) ->
                val existing = acc[pubkey]
                if (existing != null && existing.createdAt > createdAt) {
                    acc
                } else {
                    acc + (pubkey to Entry(profile, createdAt))
                }
            }
        }
    }

    fun putEvent(event: NostrEvent): NostrProfile? {
        val profile = event.toProfile() ?: return null
        put(event.pubkey, profile, event.createdAt)
        return get(event.pubkey)
    }

    fun get(pubkey: String): NostrProfile? =
        entries.value[pubkey]?.profile

    fun getAll(pubkeys: Collection<String>): Map<String, NostrProfile> =
        pubkeys.mapNotNull { pubkey ->
            entries.value[pubkey]?.profile?.let { pubkey to it }
        }.toMap()

    fun observe(pubkey: String): Flow<NostrProfile?> =
        entries
            .map { it[pubkey]?.profile }
            .distinctUntilChanged()

    fun observeUpdates(): Flow<Pair<String, NostrProfile>> = profileUpdates
}
