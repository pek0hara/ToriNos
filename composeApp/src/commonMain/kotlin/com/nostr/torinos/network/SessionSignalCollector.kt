package com.nostr.torinos.network

import com.nostr.torinos.util.refactorTraceLog
import com.nostr.torinos.util.logException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Liveの欠落を有限取得で補完する。handlerと補完の反映を同じMutexで直列化する。 */
internal suspend fun SubscriptionSession.collectRecovering(handler: suspend (SubscriptionSignal) -> Unit) = coroutineScope {
    val actual = this@collectRecovering as? SubscriptionSessionImpl
    if (actual?.liveRecoverySpec() == null) {
        signals.collect(handler)
        return@coroutineScope
    }
    val applyMutex = Mutex()
    val recovery = launch {
        deliveryState.collect { delivery ->
            if (delivery !is SessionDeliveryState.NeedsResync) return@collect
            refactorTraceLog("Recovery") { "needed generation=${delivery.generation} revision=${delivery.revision} relays=${delivery.relays.size}" }
            var failures = 0
            while (actual.deliveryState.value is SessionDeliveryState.NeedsResync) {
                delay(RelayRetryPolicy.history(maxFailures = 4).delayAfter(++failures) ?: return@collect)
                val current = actual.deliveryState.value as? SessionDeliveryState.NeedsResync ?: break
                val spec = actual.liveRecoverySpec() ?: break
                val barrier = actual.recoveryBarrier()
                refactorTraceLog("Recovery") { "start attempt=$failures generation=${barrier.generation} revision=${barrier.revision}" }
                var complete = true
                try {
                    // coverageの判定が曖昧になる複合REQにせず、元の条件を1つずつ取り直す。
                    for ((index, filter) in spec.filters.withIndex()) {
                        val result = fetchFiniteRange(
                            spec.copy(id = "recover-${spec.id.take(20)}-${barrier.revision}-$index",
                                filters = listOf(filter), target = RelayTarget.Explicit(current.relays),
                                behavior = SubscriptionBehavior.Fetch(10_000)),
                            onEvent = { relay, event -> applyMutex.withLock { handler(SubscriptionSignal.Event(relay, event, false)) } },
                        )
                        if (!result.rangeComplete) complete = false
                        if (result.relays.values.any { it.outcome.verdict() == RelayFetchVerdict.Stop }) return@collect
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    logException("SessionSignalCollector", error, "Live recovery failed")
                    complete = false
                }
                if (complete) {
                    actual.awaitRecoveryBarrier(barrier)
                    val acknowledged = actual.acknowledgeRecovery(barrier)
                    refactorTraceLog("Recovery") { "complete acknowledged=$acknowledged generation=${barrier.generation} revision=${barrier.revision}" }
                } else refactorTraceLog("Recovery") { "incomplete attempt=$failures" }
            }
        }
    }
    try { signals.collect { signal -> applyMutex.withLock {
        try { handler(signal) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) {
            actual.reportHandlerFailure(signal)
            logException("SessionSignalCollector", error, "Live signal handler failed")
        }
    } } }
    finally { recovery.cancel() }
}
