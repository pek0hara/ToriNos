package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.coroutines.flow.Flow

data class SubscriptionSpec(
    val id: String,
    val filters: List<NostrFilter>,
    val target: RelayTarget = RelayTarget.AllEnabled,
    val behavior: SubscriptionBehavior = SubscriptionBehavior.Live,
    /** Disable when the consumer must validate before deduplicating by event ID. */
    val deduplicateEvents: Boolean = true,
    /** キャッシュ済みのリアクションを、リレーの応答より先に流す。リレーの返した件数を数える取得では切る。 */
    val replayCachedEvents: Boolean = true,
)

sealed interface SubscriptionBehavior {
    data object Live : SubscriptionBehavior

    data class Fetch(
        val timeoutMillis: Long = 10_000L,
    ) : SubscriptionBehavior {
        init {
            require(timeoutMillis > 0L) { "timeoutMillisは正の値である必要があります" }
        }
    }
}

interface SubscriptionSession {
    val id: String
    val signals: Flow<SubscriptionSignal>
    /** Fetch登録時に固定した対象。Live・旧テスト実装では空集合。 */
    val expectedRelayUrls: Set<String> get() = emptySet()
    val deliveryState: kotlinx.coroutines.flow.StateFlow<SessionDeliveryState> get() = healthyDelivery

    suspend fun update(filters: List<NostrFilter>, target: RelayTarget)
    suspend fun close()

    /**
     * 有限取得で、まだ結果のないリレーを時間切れ（[RelayOutcome.TimedOut]）にして、いま完了させる。
     * 届いた結果（EOSE・CLOSED・接続できない）はそのまま。遅いリレーをいつ待たなくするかは呼び出し側が決める。
     * 配信中の購読と、完了済みの取得では何もしない。
     */
    suspend fun finishPendingRelays() = Unit
}

sealed interface SubscriptionSignal {
    data class Event(
        val relayUrl: String,
        val event: NostrEvent,
        val isLive: Boolean,
    ) : SubscriptionSignal

    data class Eose(val relayUrl: String) : SubscriptionSignal

    data class Closed(
        val relayUrl: String,
        val reason: String,
        val retry: RetryDisposition,
    ) : SubscriptionSignal

    /**
     * 配信中の購読が切断や CLOSED で途切れた後に再開したが、再送 REQ がリレーの件数上限で
     * 切られ、[interruptedAt] から [replayOldestAt] までを取り戻せていない。購読側で取り直す。
     */
    data class Resumed(
        val relayUrl: String,
        val interruptedAt: Long,
        val replayOldestAt: Long,
    ) : SubscriptionSignal

    data class RelayUnavailable(
        val relayUrl: String,
        val reason: String,
    ) : SubscriptionSignal

    data class FetchCompleted(
        val outcomes: Map<String, RelayOutcome>,
        val timedOut: Boolean,
    ) : SubscriptionSignal
}

enum class RetryDisposition {
    RetryAfterAuth,
    RetryWithBackoff,
    RetryOnFilterChange,
    DoNotRetry,
}

sealed interface RelayOutcome {
    data object Eose : RelayOutcome
    /** [retry]は CLOSED の理由から決めた、このリレーへ送り直してよいか。 */
    data class Closed(val reason: String, val retry: RetryDisposition = RetryDisposition.RetryWithBackoff) : RelayOutcome
    data class Unavailable(val reason: String) : RelayOutcome
    data object TimedOut : RelayOutcome
}
