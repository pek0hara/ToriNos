package com.nostr.torinos.network

import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock

/** 検証済みEVENTの受信元だけを記録する。キャッシュ不在は所在不明。 */
internal class EventRelayHintStore(private val maxEntries: Int = 10_000) {
    init { require(maxEntries > 0) }
    private val lock = SynchronousLock()
    private val sources = LinkedHashMap<String, LinkedHashSet<String>>()
    fun observe(id: String, relayUrl: String) = lock.withLock {
        if (!relayUrl.startsWith("wss://") && !relayUrl.startsWith("ws://")) return@withLock
        val relays = sources.remove(id) ?: linkedSetOf()
        relays.remove(relayUrl)
        relays.add(relayUrl)
        while (relays.size > 32) relays.remove(relays.first())
        sources[id] = relays
        while (sources.size > maxEntries) sources.remove(sources.keys.first())
    }
    fun get(id: String): String? = lock.withLock { sources[id]?.lastOrNull() }
    fun getAll(id: String): Set<String> = lock.withLock { sources[id]?.toSet().orEmpty() }
}
