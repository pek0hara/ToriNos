package com.nostr.torinos.engagement

import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 送信中のいいね・絵文字リアクション・リポストの操作。アカウントごとに1つで、フィードとポスト詳細が共有する。
 *
 * 表示は「付随情報ストアから導出した値」にこの操作を重ねて作る（[EngagementReducer.rebase]）。
 * 送信が終わったら、成功・失敗にかかわらず操作を外す（成功時は送ったイベントがストアに入っている）。
 */
class PendingEngagementOperations {
    private class Subscriber {
        val pending = mutableSetOf<String>()
        val signal = Channel<Unit>(Channel.CONFLATED)
    }

    private val lock = SynchronousLock()
    private val operations = mutableMapOf<String, Map<EngagementSlot, PendingEngagementOperation>>()
    private val subscribers = mutableListOf<Subscriber>()

    /** 送信中の操作が変わった投稿ID。受け手ごとに溜めて渡す。 */
    val changes: Flow<Set<String>> = flow {
        val subscriber = Subscriber()
        lock.withLock { subscribers += subscriber }
        try {
            for (unit in subscriber.signal) {
                val batch = lock.withLock { subscriber.pending.toSet().also { subscriber.pending.clear() } }
                if (batch.isNotEmpty()) emit(batch)
            }
        } finally {
            lock.withLock { subscribers -= subscriber }
        }
    }

    fun operations(eventId: String): Map<EngagementSlot, PendingEngagementOperation> =
        lock.withLock { operations[eventId].orEmpty() }

    fun put(eventId: String, slot: EngagementSlot, operation: PendingEngagementOperation) {
        lock.withLock { operations[eventId] = operations[eventId].orEmpty() + (slot to operation) }
        notifyChanged(eventId)
    }

    fun remove(eventId: String, operationId: EngagementOperationId) {
        val changed = lock.withLock {
            val current = operations[eventId] ?: return@withLock false
            val remaining = current.filterValues { it.id != operationId }
            if (remaining.size == current.size) return@withLock false
            if (remaining.isEmpty()) operations.remove(eventId) else operations[eventId] = remaining
            true
        }
        if (changed) notifyChanged(eventId)
    }

    private fun notifyChanged(eventId: String) {
        val targets = lock.withLock { subscribers.onEach { it.pending += eventId }.toList() }
        targets.forEach { it.signal.trySend(Unit) }
    }
}

/** アカウント（セッション）ごとの [PendingEngagementOperations]。 */
object PendingEngagementRegistry {
    private val lock = SynchronousLock()
    private val bySession = mutableMapOf<String, PendingEngagementOperations>()

    fun forSession(sessionId: String?): PendingEngagementOperations = lock.withLock {
        bySession.getOrPut(sessionId ?: ANONYMOUS) { PendingEngagementOperations() }
    }

    internal fun clearForTest() = lock.withLock { bySession.clear() }

    private const val ANONYMOUS = "anonymous"
}
