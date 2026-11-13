package com.nostr.torinos.network

import com.nostr.torinos.account.SavedAccountPubkeys
import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 端末に保存するプロフィールの最大件数。保存済みアカウントを優先し、残りは取得の新しい順。 */
internal const val MAX_PERSISTED_PROFILES = 1_000

/** [ProfileCache]のkind:0を端末に保存し、起動時に読み戻す(profile-cache-design「永続キャッシュ」)。 */
internal fun profileCachePersistence(
    storage: LocalTextStore?,
    scope: CoroutineScope,
    accountPubkeys: Flow<Set<String>>,
) = CachePersistence(
    name = "ProfilePersistence",
    storage = storage,
    scope = scope,
    accountPubkeys = accountPubkeys,
    pin = ProfileCache::setPinnedPubkeys,
    version = ProfileCache.persistVersion,
    restore = { content -> ProfileCache.restore(decodeProfileStore(content)) },
    encode = ::encodeProfileCache,
    describe = {
        val entries = ProfileCache.snapshotEntries().values
        "entries=${entries.size} withEvent=${entries.count { it.event != null }} pinned=${ProfileCache.pinnedPubkeys().size}"
    },
)

internal val AppProfilePersistence = profileCachePersistence(
    storage = platformCacheTextStore("profile_cache.json"),
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    accountPubkeys = SavedAccountPubkeys,
)

private fun encodeProfileCache(): String {
    val pinned = ProfileCache.pinnedPubkeys()
    val entries = ProfileCache.snapshotEntries().values
        .mapNotNull { entry -> entry.event?.let { it to entry.fetchedAt } }
        .sortedWith(compareBy({ it.first.pubkey !in pinned }, { -it.second }))
        .take(MAX_PERSISTED_PROFILES)
    return encodeProfileStore(entries)
}

@Serializable
private data class StoredProfileFile(
    val version: Int,
    val entries: List<StoredProfile> = emptyList(),
)

@Serializable
private data class StoredProfile(
    val event: NostrEvent,
    val fetchedAt: Long,
)

private val profileStoreJson = Json { ignoreUnknownKeys = true }
private const val ProfileStoreVersion = 1

internal fun encodeProfileStore(entries: List<Pair<NostrEvent, Long>>): String =
    profileStoreJson.encodeToString(
        StoredProfileFile.serializer(),
        StoredProfileFile(
            version = ProfileStoreVersion,
            entries = entries.map { (event, fetchedAt) -> StoredProfile(event, fetchedAt) },
        ),
    )

/** 壊れた内容や未知の版は空として扱う。kind:0以外は読み捨てる。 */
internal fun decodeProfileStore(content: String): List<Pair<NostrEvent, Long>> {
    val file = runCatching { profileStoreJson.decodeFromString(StoredProfileFile.serializer(), content) }
        .getOrNull()
        ?.takeIf { it.version == ProfileStoreVersion }
        ?: return emptyList()
    return file.entries.filter { it.event.kind == 0 }.map { it.event to it.fetchedAt }
}
