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
import kotlinx.coroutines.flow.update

sealed interface SessionDeliveryState {
    data object Healthy : SessionDeliveryState
    data class NeedsResync(val generation: Long, val revision: Long, val relays: Set<String>) : SessionDeliveryState
    data object Closed : SessionDeliveryState
}

internal val healthyDelivery: StateFlow<SessionDeliveryState> = MutableStateFlow(SessionDeliveryState.Healthy).asStateFlow()
internal data class RecoveryBarrier(val generation: Long, val revision: Long, val sequence: Long)

/**
 * 信号の受理と、利用者による反映を区別する単一collector用mailbox。
 *
 * ロックの中ではメモリ上の状態を変えるだけにし、チャンネルへの送信や[StateFlow]の更新はロックの外で行う。
 * Main.immediate の collector はその場で再開し、ここへ戻ってロックを取るため、iOS の再入できないロックでは止まる。
 */
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
    // 判断に使う最新の状態。[state]へは[publishState]が順に写す。
    private var latest: SessionDeliveryState = SessionDeliveryState.Healthy
    private var published: SessionDeliveryState = SessionDeliveryState.Healthy
    private var publishing = false

    val signals: Flow<SubscriptionSignal> = flow {
        for (delivery in channel) {
            var applied = false
            try { emit(delivery.signal); applied = true }
            finally {
                mutate {
                    pending.remove(delivery.sequence)
                    if (!applied && !lossless && !closed) markGap(relayOf(delivery.signal))
                }
                progress.update { it + 1 }
            }
        }
    }

    fun configure(relays: Set<String>, changed: Boolean) = mutate {
        targetRelays = relays.toSet()
        if (changed) {
            generation++
            if (latest is SessionDeliveryState.NeedsResync) markGap(targetRelays)
        }
    }

    fun emit(signal: SubscriptionSignal) {
        val delivery = lock.withLock {
            if (closed) return
            // 登録を先に行い、別スレッドのcollectorが反映済みとする競合を防ぐ。
            Delivery(++sequence, signal).also { pending += it.sequence }
        }
        if (channel.trySend(delivery).isSuccess) return
        mutate {
            pending.remove(delivery.sequence)
            if (!lossless && !closed) markGap(relayOf(signal))
        }
        progress.update { it + 1 }
    }

    /** [lock]を持っている間だけ呼ぶ。 */
    private fun markGap(relays: Set<String>) {
        val previous = latest as? SessionDeliveryState.NeedsResync
        latest = SessionDeliveryState.NeedsResync(generation, ++gapRevision,
            ((previous?.relays.orEmpty() + relays) intersect targetRelays).ifEmpty { targetRelays })
    }

    fun reportHandlerFailure(signal: SubscriptionSignal) = mutate {
        if (!closed && !lossless) markGap(relayOf(signal))
    }

    fun barrier(): RecoveryBarrier = lock.withLock { RecoveryBarrier(generation, gapRevision, sequence) }

    suspend fun awaitApplied(barrier: RecoveryBarrier) {
        progress.first { lock.withLock { closed || pending.none { it <= barrier.sequence } } }
    }

    fun acknowledge(barrier: RecoveryBarrier): Boolean = mutate {
        val current = latest as? SessionDeliveryState.NeedsResync ?: return@mutate false
        if (current.generation != barrier.generation || current.revision != barrier.revision ||
            pending.any { it <= barrier.sequence }) return@mutate false
        // 同じrevisionの有限再取得成功が、handler失敗で失った信号の回復証拠になる。
        latest = SessionDeliveryState.Healthy
        true
    }

    fun close() {
        val closing = mutate {
            if (closed) return@mutate false
            closed = true
            latest = SessionDeliveryState.Closed
            true
        }
        if (!closing) return
        channel.close()
        progress.update { it + 1 }
    }

    private inline fun <T> mutate(block: () -> T): T {
        val result = lock.withLock(block)
        publishState()
        return result
    }

    /**
     * [latest]を[state]へ写す。写している間に再開した処理が状態を変えても、写す役は1つだけにして、
     * その変更を同じループで拾う。これで古い値が後から上書きすることはない。
     */
    private fun publishState() {
        lock.withLock {
            if (publishing || latest == published) return
            publishing = true
        }
        while (true) {
            val next = lock.withLock {
                if (latest == published) {
                    publishing = false
                    return
                }
                latest.also { published = it }
            }
            state.value = next
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
