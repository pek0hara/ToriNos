package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

object ProfileCache {
    data class Entry(
        val profile: NostrProfile,
        val eventId: String?,
        val createdAt: Long,
    )

    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val entries: StateFlow<Map<String, Entry>> = _entries.asStateFlow()

    /**
     * 編集直後など、対応する kind:0 イベントをまだ受信していないプロフィールを即時反映する。
     * 既存イベントの版情報は維持し、それより新しいイベントを受信した時だけ置き換えられる。
     */
    fun putOptimistic(pubkey: String, profile: NostrProfile) {
        _entries.update { current ->
            val existing = current[pubkey]
            current + (
                pubkey to Entry(
                    profile = profile,
                    eventId = existing?.eventId,
                    createdAt = existing?.createdAt ?: Long.MIN_VALUE,
                )
            )
        }
    }

    fun putEvent(event: NostrEvent): NostrProfile? {
        require(event.kind == PROFILE_KIND) { "kind:0 以外は保存できません" }
        val profile = event.toProfile() ?: return null
        _entries.update { current ->
            val existing = current[event.pubkey]
            if (existing == null || event.isNewerThan(existing)) {
                current + (
                    event.pubkey to Entry(
                        profile = profile,
                        eventId = event.id,
                        createdAt = event.createdAt,
                    )
                )
            } else {
                current
            }
        }
        return get(event.pubkey)
    }

    fun get(pubkey: String): NostrProfile? =
        entries.value[pubkey]?.profile

    fun getAll(pubkeys: Collection<String>): Map<String, NostrProfile> {
        val snapshot = entries.value
        return pubkeys.mapNotNull { pubkey ->
            snapshot[pubkey]?.profile?.let { pubkey to it }
        }.toMap()
    }

    fun observe(pubkey: String): Flow<NostrProfile?> =
        entries
            .map { it[pubkey]?.profile }
            .distinctUntilChanged()

    private fun NostrEvent.isNewerThan(other: Entry): Boolean =
        eventIdIsMissing(other) ||
            createdAt > other.createdAt ||
            (createdAt == other.createdAt && id < other.eventId!!)

    private fun eventIdIsMissing(entry: Entry): Boolean = entry.eventId == null

    private const val PROFILE_KIND = 0
}
