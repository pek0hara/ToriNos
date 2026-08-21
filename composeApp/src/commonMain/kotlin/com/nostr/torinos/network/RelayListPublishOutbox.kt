package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PendingRelayListPublish(
    val event: NostrEvent,
    val pendingRelayUrls: Set<String>,
)

/** アカウントごとに最新の未送信 kind:10002 だけを永続化する。 */
internal object RelayListPublishOutbox {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = mutableMapOf<String, PendingRelayListPublish?>()

    suspend fun get(pubkey: String): PendingRelayListPublish? = mutex.withLock {
        if (memory.containsKey(pubkey)) return@withLock memory[pubkey]
        val pending = runCatching {
            LocalSettingsStorage.getString(cacheKey(pubkey))
                ?.let { json.decodeFromString<PendingRelayListPublish>(it) }
                ?.takeIf { it.event.kind == RELAY_LIST_KIND && it.event.pubkey == pubkey }
        }.getOrNull()
        memory[pubkey] = pending
        pending
    }

    suspend fun enqueue(event: NostrEvent, relayUrls: Collection<String>) = mutex.withLock {
        require(event.kind == RELAY_LIST_KIND) { "kind:10002 以外は保存できません" }
        val pending = PendingRelayListPublish(
            event = event,
            pendingRelayUrls = relayUrls.mapNotNull(::normalizeRelayUrl).toSet(),
        )
        memory[event.pubkey] = pending
        persist(event.pubkey, pending)
    }

    suspend fun markSucceeded(event: NostrEvent, relayUrls: Collection<String>) = mutex.withLock {
        val current = memory[event.pubkey] ?: loadLocked(event.pubkey) ?: return@withLock
        if (current.event.id != event.id) return@withLock
        val remaining = current.pendingRelayUrls - relayUrls.mapNotNull(::normalizeRelayUrl).toSet()
        val next = current.copy(pendingRelayUrls = remaining).takeIf { remaining.isNotEmpty() }
        memory[event.pubkey] = next
        persist(event.pubkey, next)
    }

    suspend fun discardIfOlderThan(event: NostrEvent) = mutex.withLock {
        val current = memory[event.pubkey] ?: loadLocked(event.pubkey) ?: return@withLock
        if (event.isNewerThan(current.event)) {
            memory[event.pubkey] = null
            persist(event.pubkey, null)
        }
    }

    private suspend fun loadLocked(pubkey: String): PendingRelayListPublish? {
        if (memory.containsKey(pubkey)) return memory[pubkey]
        val pending = runCatching {
            LocalSettingsStorage.getString(cacheKey(pubkey))
                ?.let { json.decodeFromString<PendingRelayListPublish>(it) }
        }.getOrNull()
        memory[pubkey] = pending
        return pending
    }

    private suspend fun persist(pubkey: String, pending: PendingRelayListPublish?) {
        runCatching {
            LocalSettingsStorage.putString(
                cacheKey(pubkey),
                pending?.let(json::encodeToString),
            )
        }
    }

    private fun NostrEvent.isNewerThan(other: NostrEvent): Boolean =
        createdAt > other.createdAt || (createdAt == other.createdAt && id < other.id)

    private fun cacheKey(pubkey: String): String = "$CACHE_KEY_PREFIX$pubkey"

    private const val RELAY_LIST_KIND = 10002
    private const val CACHE_KEY_PREFIX = "relay_list_publish_outbox_"
}
