package com.nostr.torinos.ui.feed

import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** 通常履歴・差分・切断補完で共有する。close完了まで同じリレーの次のREQを送らない。 */
internal class RelayFetchArbiter {
    private val locks = mutableMapOf<String, Mutex>()

    suspend fun open(url: String, gateway: FeedSubscriptionGateway, spec: SubscriptionSpec): SubscriptionSession {
        val lock = locks.getOrPut(url) { Mutex() }
        lock.lock()
        val session = try {
            gateway.open(spec)
        } catch (error: Throwable) {
            lock.unlock()
            throw error
        }
        return object : SubscriptionSession {
            private val closing = Mutex()
            private var closed = false
            override val id = session.id
            override val signals = flow {
                try { emitAll(session.signals) } finally { close() }
            }
            override suspend fun update(filters: List<com.nostr.torinos.model.NostrFilter>, target: com.nostr.torinos.network.RelayTarget) =
                session.update(filters, target)

            override suspend fun close() = withContext(NonCancellable) {
                closing.lock()
                try {
                    if (!closed) {
                        closed = true
                        try { session.close() } finally { lock.unlock() }
                    }
                } finally { closing.unlock() }
            }
        }
    }
}
