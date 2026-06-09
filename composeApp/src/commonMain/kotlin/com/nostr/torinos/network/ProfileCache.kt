package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object ProfileCache {
    data class Entry(
        val profile: NostrProfile,
        val createdAt: Long,
    )

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    fun put(pubkey: String, profile: NostrProfile, createdAt: Long = Long.MIN_VALUE) {
        entries.update { current ->
            val existing = current[pubkey]
            if (existing != null && existing.createdAt > createdAt) {
                current
            } else {
                current + (pubkey to Entry(profile, createdAt))
            }
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
        return profile
    }

    fun get(pubkey: String): NostrProfile? =
        entries.value[pubkey]?.profile

    fun getAll(pubkeys: Collection<String>): Map<String, NostrProfile> =
        pubkeys.mapNotNull { pubkey ->
            entries.value[pubkey]?.profile?.let { pubkey to it }
        }.toMap()
}
