package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.coroutines.flow.Flow

data class SubscriptionSpec(
    val id: String,
    val filters: List<NostrFilter>,
    val target: RelayTarget = RelayTarget.AllEnabled,
    val behavior: SubscriptionBehavior = SubscriptionBehavior.Live,
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

    suspend fun update(filters: List<NostrFilter>, target: RelayTarget)
    suspend fun close()
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
    data class Closed(val reason: String) : RelayOutcome
    data class Unavailable(val reason: String) : RelayOutcome
    data object TimedOut : RelayOutcome
}
