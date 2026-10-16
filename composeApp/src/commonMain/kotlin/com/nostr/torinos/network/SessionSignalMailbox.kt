package com.nostr.torinos.network

import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

sealed interface SessionDeliveryState {
    data object Healthy : SessionDeliveryState
    data class NeedsResync(val generation: Long, val revision: Long, val relays: Set<String>) : SessionDeliveryState
    data object Closed : SessionDeliveryState
}

internal val healthyDelivery: StateFlow<SessionDeliveryState> = MutableStateFlow(SessionDeliveryState.Healthy).asStateFlow()
internal data class RecoveryBarrier(val generation: Long, val revision: Long, val sequence: Long)

/** 信号の受理と、利用者による反映を区別する単一collector用mailbox。 */
internal class SessionSignalMailbox(private val lossless: Boolean, capacity: Int = 512) {
    private data class Delivery(val sequence: Long, val signal: SubscriptionSignal)
    private val lock = SynchronousLock()
    private val channel = Channel<Delivery>(if (lossless) Channel.UNLIMITED else capacity)
    private val state = MutableStateFlow<SessionDeliveryState>(SessionDeliveryState.Healthy)
    val deliveryState = state.asStateFlow()
    private val progress = MutableStateFlow(0L)
    private val pending = mutableSetOf<Long>()
    private var sequence = 0L
    private var generation = 0L
    private var gapRevision = 0L
    private var closed = false
    private var targetRelays = emptySet<String>()

    val signals: Flow<SubscriptionSignal> = flow {
        for (delivery in channel) {
            var applied = false
            try { emit(delivery.signal); applied = true }
            finally {
                lock.withLock {
                    pending.remove(delivery.sequence)
                    if (!applied && !lossless && !closed) markGap(relayOf(delivery.signal))
                    progress.value++
                }
            }
        }
    }

    fun configure(relays: Set<String>, changed: Boolean) = lock.withLock {
        targetRelays = relays.toSet()
        if (changed) {
            generation++
            if (state.value is SessionDeliveryState.NeedsResync) markGap(targetRelays)
        }
    }

    fun emit(signal: SubscriptionSignal) = lock.withLock {
        if (closed) return@withLock
        val delivery = Delivery(++sequence, signal)
        // 登録を先に行い、別スレッドのcollectorが反映済みとする競合を防ぐ。
        pending += delivery.sequence
        if (channel.trySend(delivery).isFailure) {
            pending.remove(delivery.sequence)
            if (!lossless) markGap(relayOf(signal))
        }
    }

    private fun markGap(relays: Set<String>) {
        val previous = state.value as? SessionDeliveryState.NeedsResync
        state.value = SessionDeliveryState.NeedsResync(generation, ++gapRevision,
            ((previous?.relays.orEmpty() + relays) intersect targetRelays).ifEmpty { targetRelays })
    }

    fun reportHandlerFailure(signal: SubscriptionSignal) = lock.withLock {
        if (!closed && !lossless) markGap(relayOf(signal))
    }

    fun barrier(): RecoveryBarrier = lock.withLock { RecoveryBarrier(generation, gapRevision, sequence) }

    suspend fun awaitApplied(barrier: RecoveryBarrier) {
        progress.first { lock.withLock { closed || pending.none { it <= barrier.sequence } } }
    }

    fun acknowledge(barrier: RecoveryBarrier): Boolean = lock.withLock {
        val current = state.value as? SessionDeliveryState.NeedsResync ?: return@withLock false
        if (current.generation != barrier.generation || current.revision != barrier.revision ||
            pending.any { it <= barrier.sequence }) return@withLock false
        // 同じrevisionの有限再取得成功が、handler失敗で失った信号の回復証拠になる。
        state.value = SessionDeliveryState.Healthy
        true
    }

    fun close() = lock.withLock {
        if (!closed) {
            closed = true
            channel.close()
            state.value = SessionDeliveryState.Closed
            progress.value++
        }
    }

    private fun relayOf(signal: SubscriptionSignal): Set<String> = when (signal) {
        is SubscriptionSignal.Event -> setOf(signal.relayUrl)
        is SubscriptionSignal.Eose -> setOf(signal.relayUrl)
        is SubscriptionSignal.Closed -> setOf(signal.relayUrl)
        is SubscriptionSignal.Resumed -> setOf(signal.relayUrl)
        is SubscriptionSignal.RelayUnavailable -> setOf(signal.relayUrl)
        is SubscriptionSignal.FetchCompleted -> signal.outcomes.keys
    }
}
