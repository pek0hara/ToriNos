package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * バッファ付きの有限購読で即時応答も取りこぼさず、キャンセル時にも購読を閉じる。
 *
 * [settleAfterFirstEoseMillis]を指定すると、どれか1つのリレーが EOSE を返してからその時間だけ
 * 他リレーを待ち、それでも揃わなければ成功として打ち切る。チャンネル推奨リレーのように応答しない
 * 外部リレーが混ざる複数リレー取得で、1件の無応答のためにページ全体が timeout・未完了になるのを防ぐ。
 * 未指定時は従来どおり全リレーの EOSE を完了条件とする。
 */
internal suspend fun fetchChannelEvents(
    spec: SubscriptionSpec,
    open: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    settleAfterFirstEoseMillis: Long? = null,
    /** 観測元リレーが必要な呼び出し側向け(返信の relay hint、第16.8節)。 */
    onRelayEvent: (suspend (relayUrl: String, event: NostrEvent) -> Unit)? = null,
    onEvent: suspend (NostrEvent) -> Unit,
): Boolean {
    val timeout = (spec.behavior as SubscriptionBehavior.Fetch).timeoutMillis
    var completed = false
    var session: SubscriptionSession? = null
    try {
        withTimeoutOrNull(timeout) {
            coroutineScope {
                val done = CompletableDeferred<Unit>()
                var settleJob: Job? = null
                val opened = open(spec)
                session = opened
                val collector = launch {
                    opened.signals.collect { signal ->
                        when (signal) {
                            is SubscriptionSignal.Event -> {
                                onRelayEvent?.invoke(signal.relayUrl, signal.event)
                                onEvent(signal.event)
                            }
                            is SubscriptionSignal.Eose -> if (settleAfterFirstEoseMillis != null && settleJob == null) {
                                settleJob = launch {
                                    delay(settleAfterFirstEoseMillis)
                                    completed = true
                                    done.complete(Unit)
                                }
                            }
                            is SubscriptionSignal.FetchCompleted -> {
                                val eoseCount = signal.outcomes.values.count { it is RelayOutcome.Eose }
                                completed = if (settleAfterFirstEoseMillis != null) {
                                    eoseCount > 0
                                } else {
                                    !signal.timedOut && signal.outcomes.isNotEmpty() &&
                                        eoseCount == signal.outcomes.size
                                }
                                done.complete(Unit)
                            }
                            else -> Unit
                        }
                    }
                    done.complete(Unit)
                }
                done.await()
                collector.cancel()
                settleJob?.cancel()
            }
        }
    } finally {
        withContext(NonCancellable) { session?.close() }
    }
    return completed
}
