package com.nostr.torinos.emoji

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.util.fetchTraceLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class RelayFetchResult(val events: List<NostrEvent>, val complete: Boolean)

/**
 * 有限購読で、全リレーの応答（EOSE・拒否・接続不可）か購読のタイムアウトまで受け取り、購読を閉じる。
 * 置換可能イベントの最新を選ぶため、最初に応答したリレーだけで終えない（速いリレーが古い版を持つことがある）。
 * [RelayFetchResult.complete]は全リレーが EOSE を返したとき true。タイムアウトでも受け取った分は返す。
 */
internal suspend fun fetchEventsFromRelays(
    spec: SubscriptionSpec,
    open: suspend (SubscriptionSpec) -> SubscriptionSession,
    accept: (NostrEvent) -> Boolean,
): RelayFetchResult {
    val timeout = (spec.behavior as SubscriptionBehavior.Fetch).timeoutMillis
    val events = linkedMapOf<String, NostrEvent>()
    var complete = false
    val session = open(spec)
    try {
        // FetchCompleted が来ない異常時にも止まるよう、購読のタイムアウトより少し長く待つ。
        withTimeoutOrNull(timeout + 1_000) {
            session.signals.first { signal ->
                when (signal) {
                    is SubscriptionSignal.Event -> if (accept(signal.event)) {
                        events.getOrPut(signal.event.id) { signal.event }
                        traceReceived(spec.id, signal)
                    }
                    is SubscriptionSignal.FetchCompleted -> complete = !signal.timedOut &&
                        signal.outcomes.isNotEmpty() && signal.outcomes.values.all { it is RelayOutcome.Eose }
                    else -> Unit
                }
                signal is SubscriptionSignal.FetchCompleted
            }
        }
    } finally {
        withContext(NonCancellable) { session.close() }
    }
    return RelayFetchResult(events.values.toList(), complete)
}

private fun traceReceived(subscriptionId: String, signal: SubscriptionSignal.Event) {
    fetchTraceLog { "EMOJI $subscriptionId ← ${signal.relayUrl} kind=${signal.event.kind} created_at=${signal.event.createdAt}" }
}

/** 置換可能イベントの新旧比較（NIP-01: created_at が同じなら id の小さい方）。 */
internal fun NostrEvent.isNewerThan(other: NostrEvent): Boolean =
    createdAt > other.createdAt || (createdAt == other.createdAt && id < other.id)

internal fun Iterable<NostrEvent>.newestOrNull(): NostrEvent? =
    fold(null as NostrEvent?) { newest, event -> if (newest == null || event.isNewerThan(newest)) event else newest }
