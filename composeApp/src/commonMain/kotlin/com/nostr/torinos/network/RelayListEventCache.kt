package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * リレーごとに異なる版が返る kind:10002 を、画面の再生成をまたいで最新版に固定する。
 */
object RelayListEventCache {
    private val events = MutableStateFlow<Map<String, NostrEvent>>(emptyMap())
    private val persistenceMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun get(pubkey: String): NostrEvent? = events.value[pubkey]

    suspend fun getOrLoad(pubkey: String): NostrEvent? {
        get(pubkey)?.let { return it }
        return persistenceMutex.withLock {
            get(pubkey)?.let { return@withLock it }
            val stored = runCatching {
                LocalSettingsStorage.getString(cacheKey(pubkey))
                    ?.let { json.decodeFromString<NostrEvent>(it) }
                    ?.takeIf { it.kind == RELAY_LIST_KIND && it.pubkey == pubkey }
            }.getOrNull() ?: return@withLock null
            putEvent(stored)
        }
    }

    /** 候補を保存し、現在判明している最新イベントを返す。 */
    fun putEvent(event: NostrEvent): NostrEvent {
        require(event.kind == RELAY_LIST_KIND) { "kind:10002 以外は保存できません" }
        events.update { current ->
            val cached = current[event.pubkey]
            if (cached == null || event.isNewerThan(cached)) {
                current + (event.pubkey to event)
            } else {
                current
            }
        }
        return events.value.getValue(event.pubkey)
    }

    suspend fun putEventAndPersist(event: NostrEvent): NostrEvent {
        val latest = putEvent(event)
        persistenceMutex.withLock {
            runCatching {
                LocalSettingsStorage.putString(cacheKey(event.pubkey), json.encodeToString(latest))
            }
        }
        return latest
    }

    private fun NostrEvent.isNewerThan(other: NostrEvent): Boolean =
        createdAt > other.createdAt || (createdAt == other.createdAt && id < other.id)

    private fun cacheKey(pubkey: String): String = "$CACHE_KEY_PREFIX$pubkey"

    private const val RELAY_LIST_KIND = 10002
    private const val CACHE_KEY_PREFIX = "relay_list_event_cache_"
}
