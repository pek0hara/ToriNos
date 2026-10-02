package com.nostr.torinos.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** 移行先を先に記録する。保存・旧キー削除が中断されても別アカウントへ複製しない。 */
internal class PrivateMuteListStorage(
    private val getString: suspend (String) -> String? = LocalSettingsStorage::getString,
    private val putString: suspend (String, String?) -> Unit = LocalSettingsStorage::putString,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(pubkey: String): PrivateMuteListCache = migrationMutex.withLock {
        val accountCache = getString(cacheKey(pubkey))?.let(::decodeCache)
        var owner = getString(MIGRATION_OWNER_KEY)
        if (owner == null) {
            putString(MIGRATION_OWNER_KEY, pubkey)
            owner = pubkey
        }
        if (owner != pubkey) return@withLock accountCache ?: PrivateMuteListCache()

        val legacyCache = getString(LEGACY_CACHE_KEY)
        val legacyPubkeys = getString(LEGACY_MUTED_PUBKEYS_KEY)
        val legacyWords = getString(LEGACY_NG_WORDS_KEY)
        val result = accountCache ?: legacyCache?.let(::decodeCache)
            ?: PrivateMuteListCache(
                mutedPubkeys = legacyPubkeys?.let { json.decodeFromString<List<String>>(it) }.orEmpty(),
                ngWords = legacyWords?.let { json.decodeFromString<List<String>>(it) }.orEmpty(),
            )
        if (legacyCache != null || legacyPubkeys != null || legacyWords != null) {
            // 保存が失敗した場合は旧データを残し、同じ移行先だけが再試行できる。
            save(pubkey, result)
            putString(LEGACY_CACHE_KEY, null)
            putString(LEGACY_MUTED_PUBKEYS_KEY, null)
            putString(LEGACY_NG_WORDS_KEY, null)
        }
        result
    }

    suspend fun save(pubkey: String, cache: PrivateMuteListCache) {
        putString(cacheKey(pubkey), json.encodeToString(cache))
    }

    private fun decodeCache(saved: String): PrivateMuteListCache {
        val cache = json.decodeFromString<PrivateMuteListCache>(saved)
        // 旧実装のローカル編集はsourceをnullにして保存していた。更新前の未送信変更も保護する。
        val isOldLocalEdit = "hasPendingChanges" !in json.parseToJsonElement(saved).jsonObject &&
            cache.sourceEventId == null && cache.updatedAt > 0
        return if (isOldLocalEdit) cache.copy(hasPendingChanges = true) else cache
    }

    private fun cacheKey(pubkey: String) = "private_mute_list_cache_v2_$pubkey"

    companion object {
        private val migrationMutex = Mutex()
        internal const val MIGRATION_OWNER_KEY = "private_mute_list_legacy_owner_v1"
        private const val LEGACY_CACHE_KEY = "private_mute_list_cache_v1"
        private const val LEGACY_MUTED_PUBKEYS_KEY = "muted_pubkeys"
        private const val LEGACY_NG_WORDS_KEY = "ng_words"
    }
}
