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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

object ProfileCache {
    data class Entry(
        val profile: NostrProfile,
        val eventId: String?,
        val createdAt: Long,
        val fetchedAt: Long,
        /** 永続化用の元kind:0イベント。楽観更新だけで元イベントを受信していない場合はnull。 */
        val event: NostrEvent? = null,
    )

    private data class FetchedProfile(
        val event: NostrEvent,
        val profile: NostrProfile,
    )

    private val lock = SynchronousLock()
    private val entriesByPubkey = LinkedHashMap<String, Entry>()
    /** 保存済みアカウントなど、件数上限による追い出しの対象から外すpubkey。 */
    private var pinnedPubkeys: Set<String> = emptySet()
    private val changedPubkeys = MutableSharedFlow<Set<String>>(
        replay = 1,
        extraBufferCapacity = ChangeBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val persistVersionState = MutableStateFlow(0L)

    /** 永続化すべき変更のたびに増える。永続キャッシュからの読み込み([restore])では増えない。 */
    internal val persistVersion: StateFlow<Long> = persistVersionState.asStateFlow()

    /**
     * 編集直後など、対応する kind:0 イベントをまだ受信していないプロフィールを即時反映する。
     * 既存イベントの版情報は維持し、それより新しいイベントを受信した時だけ置き換えられる。
     */
    fun putOptimistic(pubkey: String, profile: NostrProfile) {
        val now = Clock.System.now().toEpochMilliseconds()
        val changed = lock.withLock {
            val existing = entriesByPubkey[pubkey]
            // プロフィール画面は表示中のプロフィールをそのまま渡す。編集ではないので何も変えない。
            if (existing?.profile == profile) return
            entriesByPubkey[pubkey] = Entry(
                profile = profile,
                eventId = existing?.eventId,
                createdAt = existing?.createdAt ?: Long.MIN_VALUE,
                fetchedAt = now,
                // 表示中のプロフィールと一致しない元イベントを、取得し直したものとして保存しない。
                event = null,
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
                            event = event,
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

    /**
     * 永続キャッシュから読み込んだkind:0を、保存時の取得時刻のまま反映する。
     * 起動後にリレーから受信済みの新しい版や、楽観更新中のプロフィールは置き換えない。
     */
    fun restore(stored: Collection<Pair<NostrEvent, Long>>) {
        if (stored.isEmpty()) return
        val restored = stored.mapNotNull { (event, fetchedAt) ->
            if (event.kind != PROFILE_KIND) return@mapNotNull null
            event.toProfile()?.let { profile -> Triple(event, profile, fetchedAt) }
        }
        val changed = linkedSetOf<String>()
        lock.withLock {
            restored.forEach { (event, profile, fetchedAt) ->
                val existing = entriesByPubkey[event.pubkey]
                if (existing != null && (existing.eventId == null || !event.isNewerThan(existing))) return@forEach
                entriesByPubkey[event.pubkey] = Entry(
                    profile = profile,
                    eventId = event.id,
                    createdAt = event.createdAt,
                    fetchedAt = maxOf(fetchedAt, existing?.fetchedAt ?: Long.MIN_VALUE),
                    event = event,
                )
                changed.add(event.pubkey)
            }
            trimToMaximumLocked(emptySet(), changed)
        }
        cacheTraceLog { "[ProfileCache] restore input=${stored.size} changed=${changed.size}" }
        notifyChanges(changed, persist = false)
    }

    fun setPinnedPubkeys(pubkeys: Set<String>) {
        lock.withLock { pinnedPubkeys = pubkeys.toSet() }
    }

    internal fun pinnedPubkeys(): Set<String> = lock.withLock { pinnedPubkeys }

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

    /** 手元で最新の kind:0 イベント。楽観更新だけで元イベントが無ければ null。 */
    fun latestEvent(pubkey: String): NostrEvent? = lock.withLock {
        entriesByPubkey[pubkey]?.event
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
            pinnedPubkeys = emptySet()
            entriesByPubkey.keys.toSet().also { entriesByPubkey.clear() }
        }
        notifyChanges(removed)
    }

    private fun trimToMaximumLocked(protectedKeys: Set<String>, changed: MutableSet<String>) {
        while (entriesByPubkey.size > MaximumEntries) {
            val candidateKey = oldestKeyLocked { it !in protectedKeys && it !in pinnedPubkeys }
                ?: oldestKeyLocked { it !in pinnedPubkeys }
                ?: oldestKeyLocked { true }
                ?: return
            entriesByPubkey.remove(candidateKey)
            changed.add(candidateKey)
            cacheTraceLog { "[ProfileCache] evicted pubkey=$candidateKey size=${entriesByPubkey.size}" }
        }
    }

    private inline fun oldestKeyLocked(crossinline evictable: (String) -> Boolean): String? =
        entriesByPubkey.entries
            .asSequence()
            .filter { evictable(it.key) }
            .minWithOrNull(compareBy({ it.value.fetchedAt }, { it.key }))
            ?.key

    private fun notifyChanges(pubkeys: Set<String>, persist: Boolean = true) {
        if (pubkeys.isEmpty()) return
        changedPubkeys.tryEmit(pubkeys)
        if (persist) persistVersionState.update { it + 1 }
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
