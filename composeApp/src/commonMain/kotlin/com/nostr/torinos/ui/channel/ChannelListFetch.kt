package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** バッファ付きの有限購読で即時応答も取りこぼさず、キャンセル時にも購読を閉じる。 */
internal suspend fun fetchChannelEvents(
    spec: SubscriptionSpec,
    open: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    onEvent: suspend (NostrEvent) -> Unit,
): Boolean {
    val timeout = (spec.behavior as SubscriptionBehavior.Fetch).timeoutMillis
    var completed = false
    var session: SubscriptionSession? = null
    try {
        withTimeoutOrNull(timeout) {
            val opened = open(spec)
            session = opened
            opened.signals.collect { signal ->
                when (signal) {
                    is SubscriptionSignal.Event -> onEvent(signal.event)
                    is SubscriptionSignal.FetchCompleted -> completed =
                        !signal.timedOut && signal.outcomes.isNotEmpty() &&
                            signal.outcomes.values.all { it is RelayOutcome.Eose }
                    else -> Unit
                }
            }
        }
    } finally {
        withContext(NonCancellable) { session?.close() }
    }
    return completed
}
