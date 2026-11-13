package com.nostr.torinos.badge

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * 検証済みのバッジ関連イベント本体を、アプリ全体でユーザー単位・バッジ単位に保持する(設計書§14)。
 *
 * BadgeRepositoryはアカウントごとに作り直されるが、イベント本体は公開データなので、ここで
 * アカウントをまたいで共有し、再起動後の即時表示にも使う。通信は行わず、取得期限も持たない。
 * 表示リストから参照されないイベントは保持せず、削除は対象と同じ単位に入れる。
 */
internal class BadgeEventStore(
    private val maxUsers: Int = MAX_USERS,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ready: Boolean = true,
) {
    private class UserUnit(var lastUsedAt: Long) {
        var modern: NostrEvent? = null
        var legacy: NostrEvent? = null
        var relayList: NostrEvent? = null
        val sets = linkedMapOf<BadgeAddress, NostrEvent>()
        val awards = linkedMapOf<String, NostrEvent>()
        val deletions = linkedMapOf<String, NostrEvent>()
        // 表示リストから導いた参照先。表示リストかセットが変わったときだけ計算し直す。
        var setAddresses: Set<BadgeAddress> = emptySet()
        var awardIds: Set<String> = emptySet()
        var badgeAddresses: Set<BadgeAddress> = emptySet()
        var targets: Set<String> = emptySet()

        fun events(): List<NostrEvent> =
            listOfNotNull(modern, legacy, relayList) + sets.values + awards.values + deletions.values
    }

    private class BadgeUnit(val address: BadgeAddress) {
        var definition: NostrEvent? = null
        val deletions = linkedMapOf<String, NostrEvent>()

        fun isTargetOf(targets: Set<String>): Boolean =
            "a:${address.value}" in targets || definition?.let { "e:${it.id}" in targets } == true
        fun events(): List<NostrEvent> = listOfNotNull(definition) + deletions.values
    }

    // `lock` is not reentrant on iOS: private helpers named *Locked assume it is held.
    private val lock = SynchronousLock()
    private val users = linkedMapOf<String, UserUnit>()
    private val badges = mutableMapOf<BadgeAddress, BadgeUnit>()
    private val badgeReferences = mutableMapOf<BadgeAddress, Int>()
    private var pinned: Set<String> = emptySet()
    private val versionState = MutableStateFlow(0L)
    private val readyState = MutableStateFlow(ready)

    /**
     * 保持内容が変わるたびに増える。最終利用時刻の更新と、端末からの読み込み([restore])では増えない。
     * 購読前の変更も取りこぼさないよう、保存側は開始時の値と比べて使う。
     */
    val version: StateFlow<Long> = versionState.asStateFlow()

    /** 端末からの読み込みが終わったか。終わる前に読んだ著者は、終わったあと読み直す。 */
    val ready: StateFlow<Boolean> = readyState.asStateFlow()

    fun markReady() { readyState.value = true }

    fun put(events: Collection<NostrEvent>) {
        if (events.isEmpty()) return
        val now = nowMillis()
        val modified = lock.withLock { putAllLocked(events, usedAt = now) }
        if (modified) versionState.update { it + 1 }
    }

    /** ユーザーとその参照先バッジのイベント。未保持なら空。 */
    fun eventsFor(pubkey: String): List<NostrEvent> = lock.withLock {
        val user = users[pubkey] ?: return@withLock emptyList()
        user.events() + user.badgeAddresses.flatMap { badges[it]?.events().orEmpty() }
    }

    /** 表示されたユーザーの最終利用時刻を更新する。保存の要否には影響しない。 */
    fun touch(pubkey: String) {
        val now = nowMillis()
        lock.withLock { users[pubkey]?.let { it.lastUsedAt = maxOf(it.lastUsedAt, now) } }
    }

    fun setPinnedUsers(pubkeys: Set<String>) {
        lock.withLock { pinned = pubkeys.toSet() }
    }

    internal fun badgeCount(): Int = lock.withLock { badges.size }

    internal fun userCount(): Int = lock.withLock { users.size }

    /** 保存済みアカウントを優先し、残りを最終利用時刻の新しい順に[maxUsers]人まで書き出す。 */
    fun encode(maxUsers: Int): String {
        val file = lock.withLock {
            val selected = users.entries
                .sortedWith(compareBy({ it.key !in pinned }, { -it.value.lastUsedAt }))
                .take(maxUsers)
            val addresses = selected.flatMapTo(linkedSetOf()) { it.value.badgeAddresses }
            StoredBadgeFile(
                version = BadgeStoreVersion,
                users = selected.map { (pubkey, user) -> StoredBadgeUser(pubkey, user.lastUsedAt, user.events()) },
                badges = addresses.mapNotNull { address ->
                    badges[address]?.events()?.takeIf { it.isNotEmpty() }?.let { StoredBadgeUnit(address.value, it) }
                },
            )
        }
        return badgeStoreJson.encodeToString(StoredBadgeFile.serializer(), file)
    }

    /** [encode]の内容を読み込む。受信済みの新しい版は戻さない。壊れた内容・未知の版は無視する。 */
    fun restore(content: String) {
        val file = runCatching { badgeStoreJson.decodeFromString(StoredBadgeFile.serializer(), content) }
            .getOrNull()
            ?.takeIf { it.version == BadgeStoreVersion }
            ?: return
        lock.withLock {
            file.users.forEach { user ->
                // 他人のイベントは、そのユーザー単位の取り込みの規則で振り分け直す。
                putAllLocked(user.events, usedAt = user.lastUsedAt)
                users[user.pubkey]?.let { it.lastUsedAt = maxOf(it.lastUsedAt, user.lastUsedAt) }
            }
            putAllLocked(file.badges.flatMap { it.events }, usedAt = Long.MIN_VALUE)
        }
    }

    private fun putAllLocked(events: Collection<NostrEvent>, usedAt: Long): Boolean {
        // 参照元(表示リスト・セット)を先に入れ、参照先がどのユーザーに属すか決められるようにする。
        var modified = false
        events.sortedBy(::putOrder).forEach { if (putLocked(it, usedAt)) modified = true }
        return modified
    }

    private fun putLocked(event: NostrEvent, usedAt: Long): Boolean = when {
        event.isBadgeProfile() -> putProfileLocked(event, usedAt)
        event.kind == 30008 -> {
            val user = users[event.pubkey]
            val address = event.badgeAddress()
            if (user == null || address == null || address !in user.setAddresses) false
            else replaceIfNewer(user.sets[address], event) { user.sets[address] = it }
                .also { if (it) recomputeLocked(event.pubkey, user) }
        }
        event.kind == 10002 -> users[event.pubkey]?.let { user ->
            replaceIfNewer(user.relayList, event) { user.relayList = it }
        } ?: false
        event.kind == 8 -> event.tags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) }.distinct()
            .map { recipient ->
                val user = users[recipient]
                if (user == null || event.id !in user.awardIds || user.awards[event.id] == event) false
                else { user.awards[event.id] = event; true }
            }.any { it }
        event.kind == 30009 -> event.badgeAddress()?.let { badges[it] }?.let { badge ->
            replaceIfNewer(badge.definition, event) { badge.definition = it }
        } ?: false
        event.kind == 5 -> putDeletionLocked(event)
        else -> false
    }

    private fun putProfileLocked(event: NostrEvent, usedAt: Long): Boolean {
        val user = users[event.pubkey] ?: UserUnit(usedAt).also {
            users[event.pubkey] = it
            evictLocked(protect = event.pubkey)
        }
        val replaced = if (event.kind == 10008) replaceIfNewer(user.modern, event) { user.modern = it }
            else replaceIfNewer(user.legacy, event) { user.legacy = it }
        if (replaced) recomputeLocked(event.pubkey, user)
        return replaced
    }

    private fun putDeletionLocked(deletion: NostrEvent): Boolean {
        val targets = deletionTargets(deletion)
        if (targets.isEmpty()) return false
        var modified = false
        users.values.forEach { user ->
            if (targets.any(user.targets::contains) && user.deletions.put(deletion.id, deletion) != deletion) modified = true
        }
        badges.values.forEach { badge ->
            if (badge.isTargetOf(targets) && badge.deletions.put(deletion.id, deletion) != deletion) modified = true
        }
        return modified
    }

    /** 表示リストから参照先を導き、参照されなくなった授与・セット・削除・バッジ単位を外す。 */
    private fun recomputeLocked(pubkey: String, user: UserUnit) {
        val profile = selectBadgeProfile(listOfNotNull(user.modern, user.legacy), pubkey)
        val items = profile?.let { BadgeSelection.parse(it.tags).items }.orEmpty()
        user.setAddresses = items.mapNotNull { it.address }
            .filter { it.kind == 30008 && it.pubkey == pubkey }.take(MAX_SETS).toSet()
        user.sets.keys.retainAll(user.setAddresses)
        // BadgeRepository.expandと同じく、自作セットはその位置で展開し、先頭の初回解決範囲だけを持つ。
        val expanded = items.flatMap { item ->
            val address = item.address
            when {
                address?.kind == 30009 -> listOf(item)
                address != null && address in user.setAddresses -> user.sets[address]
                    ?.let { set -> BadgeSelection.parse(set.tags).items.filter { it.address?.kind == 30009 } }.orEmpty()
                else -> emptyList()
            }
        }.take(MAX_AWARDS_PER_USER)
        user.awardIds = expanded.mapNotNullTo(hashSetOf()) { it.awardId }
        user.awards.keys.retainAll(user.awardIds)
        val addresses = expanded.mapNotNullTo(linkedSetOf()) { it.address }
        updateBadgeReferencesLocked(removed = user.badgeAddresses - addresses, added = addresses - user.badgeAddresses)
        user.badgeAddresses = addresses
        user.targets = buildSet {
            listOfNotNull(user.modern, user.legacy).forEach { add("e:${it.id}") }
            user.legacy?.badgeAddress()?.let { add("a:${it.value}") }
            user.setAddresses.forEach { add("a:${it.value}") }
            user.sets.values.forEach { add("e:${it.id}") }
            user.awardIds.forEach { add("e:$it") }
        }
        user.deletions.values.removeAll { deletionTargets(it).none(user.targets::contains) }
    }

    private fun updateBadgeReferencesLocked(removed: Set<BadgeAddress>, added: Set<BadgeAddress>) {
        added.forEach { address ->
            badgeReferences[address] = (badgeReferences[address] ?: 0) + 1
            badges.getOrPut(address) { BadgeUnit(address) }
        }
        removed.forEach { address ->
            val remaining = (badgeReferences[address] ?: 1) - 1
            if (remaining > 0) badgeReferences[address] = remaining
            else { badgeReferences.remove(address); badges.remove(address) }
        }
    }

    private fun evictLocked(protect: String) {
        while (users.size > maxUsers) {
            val victim = users.entries
                .filter { it.key != protect && it.key !in pinned }
                .minByOrNull { it.value.lastUsedAt }
                ?.key
                ?: return
            val removed = users.remove(victim) ?: return
            updateBadgeReferencesLocked(removed = removed.badgeAddresses, added = emptySet())
        }
    }

    private inline fun replaceIfNewer(existing: NostrEvent?, event: NostrEvent, store: (NostrEvent) -> Unit): Boolean {
        if (existing != null && (existing == event || !event.badgeNewerThan(existing))) return false
        store(event)
        return true
    }

    private fun putOrder(event: NostrEvent): Int = when {
        event.isBadgeProfile() -> 0
        event.kind == 30008 -> 1
        event.kind == 10002 -> 2
        event.kind == 8 -> 3
        event.kind == 30009 -> 4
        else -> 5
    }

    private fun deletionTargets(deletion: NostrEvent): Set<String> = deletion.tags.mapNotNullTo(hashSetOf()) { tag ->
        when (tag.firstOrNull()) {
            "e" -> tag.getOrNull(1)?.let { "e:$it" }
            "a" -> tag.getOrNull(1)?.let { "a:$it" }
            else -> null
        }
    }

    companion object {
        const val MAX_USERS = 1_000
        const val MAX_PERSISTED_USERS = 500
        /** BadgeRepositoryの初回解決範囲・セット取得数と同じ。 */
        const val MAX_AWARDS_PER_USER = 100
        const val MAX_SETS = 10
    }
}

@Serializable
private data class StoredBadgeFile(
    val version: Int,
    val users: List<StoredBadgeUser> = emptyList(),
    val badges: List<StoredBadgeUnit> = emptyList(),
)

@Serializable
private data class StoredBadgeUser(val pubkey: String, val lastUsedAt: Long, val events: List<NostrEvent>)

@Serializable
private data class StoredBadgeUnit(val address: String, val events: List<NostrEvent>)

private val badgeStoreJson = Json { ignoreUnknownKeys = true }
private const val BadgeStoreVersion = 1
