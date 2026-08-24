package com.nostr.torinos.ui.timeline

import com.nostr.torinos.network.SubscriptionSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmInline

@JvmInline
internal value class SubscriptionSlot(val value: String) {
    init {
        require(value.isNotBlank()) { "SubscriptionSlot must not be blank" }
    }
}

/** SubscriptionSession の置換と終了を直列化する単一所有者。 */
internal class SubscriptionOwner {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<SubscriptionSlot, SubscriptionSession>()

    suspend fun replace(slot: SubscriptionSlot, session: SubscriptionSession?) {
        mutex.withLock {
            val previous = sessions[slot]
            if (previous === session) return
            sessions.remove(slot)
            previous?.close()
            if (session != null) sessions[slot] = session
        }
    }

    suspend fun close(slot: SubscriptionSlot) {
        mutex.withLock {
            sessions.remove(slot)?.close()
        }
    }

    suspend fun closeAll() {
        mutex.withLock {
            val owned = sessions.values.toList()
            sessions.clear()
            var firstFailure: Throwable? = null
            owned.forEach { session ->
                try {
                    session.close()
                } catch (error: Throwable) {
                    if (firstFailure == null) firstFailure = error
                }
            }
            firstFailure?.let { throw it }
        }
    }
}
