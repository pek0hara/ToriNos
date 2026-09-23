package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.cacheTraceLog
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlin.time.Clock

object ProfileCache {
    data class Entry(
        val profile: NostrProfile,
        val eventId: String?,
        val createdAt: Long,
        val fetchedAt: Long,
    )

    private data class FetchedProfile(
        val event: NostrEvent,
        val profile: NostrProfile,
    )

    private val lock = SynchronousLock()
    private val entriesByPubkey = LinkedHashMap<String, Entry>()
    private val changedPubkeys = MutableSharedFlow<Set<String>>(
        replay = 1,
        extraBufferCapacity = ChangeBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 編集直後など、対応する kind:0 イベントをまだ受信していないプロフィールを即時反映する。
     * 既存イベントの版情報は維持し、それより新しいイベントを受信した時だけ置き換えられる。
     */
    fun putOptimistic(pubkey: String, profile: NostrProfile) {
        val now = Clock.System.now().toEpochMilliseconds()
        val changed = lock.withLock {
            val existing = entriesByPubkey[pubkey]
            entriesByPubkey[pubkey] = Entry(
                profile = profile,
                eventId = existing?.eventId,
                createdAt = existing?.createdAt ?: Long.MIN_VALUE,
                fetchedAt = now,
            )
            linkedSetOf(pubkey).also { trimToMaximumLocked(setOf(pubkey), it) }
        }
        notifyChanges(changed)
    }

    fun putEvent(
        event: NostrEvent,
        fetchedAt: Long = Clock.System.now().toEpochMilliseconds(),
    ): NostrProfile? {
        val profiles = putEvents(listOf(event), fetchedAt)
        return profiles[event.pubkey]
    }

    /** 複数のkind:0を一括適用し、変更通知を1回にまとめる。 */
    fun putEvents(
        events: Collection<NostrEvent>,
        fetchedAt: Long = Clock.System.now().toEpochMilliseconds(),
    ): Map<String, NostrProfile> {
        if (events.isEmpty()) return emptyMap()
        events.forEach { require(it.kind == PROFILE_KIND) { "kind:0 以外は保存できません" } }
        val fetchedProfiles = events.mapNotNull { event ->
            event.toProfile()?.let { profile -> FetchedProfile(event, profile) }
        }
        if (fetchedProfiles.isEmpty()) return emptyMap()

        val changed = linkedSetOf<String>()
        var sizeAfter = 0
        val result = lock.withLock {
            fetchedProfiles.forEach { fetched ->
                val event = fetched.event
                val existing = entriesByPubkey[event.pubkey]
                when {
                    existing == null || event.isNewerThan(existing) -> {
                        entriesByPubkey[event.pubkey] = Entry(
                            profile = fetched.profile,
                            eventId = event.id,
                            createdAt = event.createdAt,
                            fetchedAt = fetchedAt,
                        )
                        changed.add(event.pubkey)
                    }
                    fetchedAt > existing.fetchedAt -> {
                        entriesByPubkey[event.pubkey] = existing.copy(fetchedAt = fetchedAt)
                        changed.add(event.pubkey)
                    }
                }
            }
            trimToMaximumLocked(fetchedProfiles.mapTo(hashSetOf()) { it.event.pubkey }, changed)
            sizeAfter = entriesByPubkey.size
            fetchedProfiles
                .mapNotNull { fetched ->
                    entriesByPubkey[fetched.event.pubkey]?.profile?.let { fetched.event.pubkey to it }
                }
                .toMap()
        }
        cacheTraceLog {
            "[ProfileCache] putEvents input=${events.size} applied=${fetchedProfiles.size} " +
                "changed=${changed.size} size=$sizeAfter"
        }
        notifyChanges(changed)
        return result
    }

    /** リレーで再検証できた既存プロフィールの取得時刻だけを更新する。 */
    fun markFetched(pubkeys: Collection<String>, fetchedAt: Long = Clock.System.now().toEpochMilliseconds()) {
        if (pubkeys.isEmpty()) return
        val changed = lock.withLock {
            buildSet {
                pubkeys.forEach { pubkey ->
                    val existing = entriesByPubkey[pubkey]
                    if (existing != null && existing.fetchedAt < fetchedAt) {
                        entriesByPubkey[pubkey] = existing.copy(fetchedAt = fetchedAt)
                        add(pubkey)
                    }
                }
            }
        }
        notifyChanges(changed)
    }

    fun get(pubkey: String): NostrProfile? = lock.withLock {
        entriesByPubkey[pubkey]?.profile
    }

    fun getAll(pubkeys: Collection<String>): Map<String, NostrProfile> = lock.withLock {
        pubkeys.mapNotNull { pubkey ->
            entriesByPubkey[pubkey]?.profile?.let { pubkey to it }
        }.toMap()
    }

    fun observe(pubkey: String): Flow<NostrProfile?> =
        changedPubkeys
            .onStart { emit(setOf(pubkey)) }
            .filter { pubkey in it }
            .onEach { cacheTraceLog { "[ProfileCache] observe($pubkey) relevant change, refetching" } }
            .map { get(pubkey) }
            .distinctUntilChanged()

    fun observe(pubkeys: Set<String>): Flow<Map<String, NostrProfile>> =
        changedPubkeys
            .onStart { emit(pubkeys) }
            .filter { changed -> changed.any(pubkeys::contains) }
            .onEach { changed ->
                cacheTraceLog {
                    "[ProfileCache] observe(${pubkeys.size} keys) relevant=${changed.count(pubkeys::contains)}"
                }
            }
            .map { getAll(pubkeys) }
            .distinctUntilChanged()

    internal fun observeChanges(): Flow<Set<String>> =
        changedPubkeys.onStart { emit(emptySet()) }

    internal fun snapshotEntries(): Map<String, Entry> = lock.withLock {
        entriesByPubkey.toMap()
    }


    internal fun getEntry(pubkey: String): Entry? = lock.withLock {
        entriesByPubkey[pubkey]
    }

    internal fun clearForTest() {
        val removed = lock.withLock {
            entriesByPubkey.keys.toSet().also { entriesByPubkey.clear() }
        }
        notifyChanges(removed)
    }

    private fun trimToMaximumLocked(protectedKeys: Set<String>, changed: MutableSet<String>) {
        while (entriesByPubkey.size > MaximumEntries) {
            val candidateKey = entriesByPubkey.entries
                .asSequence()
                .filter { it.key !in protectedKeys }
                .minWithOrNull(compareBy({ it.value.fetchedAt }, { it.key }))
                ?.key
                ?: entriesByPubkey.entries
                    .minWithOrNull(compareBy({ it.value.fetchedAt }, { it.key }))
                    ?.key
                ?: return
            entriesByPubkey.remove(candidateKey)
            changed.add(candidateKey)
            cacheTraceLog { "[ProfileCache] evicted pubkey=$candidateKey size=${entriesByPubkey.size}" }
        }
    }

    private fun notifyChanges(pubkeys: Set<String>) {
        if (pubkeys.isNotEmpty()) changedPubkeys.tryEmit(pubkeys)
    }

    private fun NostrEvent.isNewerThan(other: Entry): Boolean =
        eventIdIsMissing(other) ||
            createdAt > other.createdAt ||
            (createdAt == other.createdAt && id < other.eventId!!)

    private fun eventIdIsMissing(entry: Entry): Boolean = entry.eventId == null

    private const val PROFILE_KIND = 0
    private const val MaximumEntries = 2_000
    private const val ChangeBufferCapacity = 128
}
