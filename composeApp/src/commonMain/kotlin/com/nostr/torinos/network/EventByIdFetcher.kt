package com.nostr.torinos.network

import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlin.random.Random

sealed interface TargetLoadState {
    data object Idle : TargetLoadState
    data object Loading : TargetLoadState
    data class Resolved(val event: NostrEvent) : TargetLoadState
    data object NotFoundInQueriedRelays : TargetLoadState
    data class Unavailable(val reason: TargetFetchFailure) : TargetLoadState
}

enum class TargetFetchFailure { NoRelay, Incomplete, InvalidResponse, Unexpected }

data class EventByIdResult(
    val missingState: TargetLoadState,
    val outcomes: Map<String, RelayOutcome> = emptyMap(),
)

fun interface TargetEventFetcher {
    suspend fun fetch(ids: Set<String>, onEvent: (NostrEvent) -> Unit): EventByIdResult
}

fun isFullEventId(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

/** Finite, exact-ID lookup. Kind belongs to presentation, never to this filter. */
class EventByIdFetcher(
    private val openSession: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    private val validate: suspend (NostrEvent) -> Boolean = { event ->
        withContext(Dispatchers.Default) { isValidEvent(event) }
    },
) : TargetEventFetcher {
    override suspend fun fetch(ids: Set<String>, onEvent: (NostrEvent) -> Unit): EventByIdResult {
        require(ids.all(::isFullEventId))
        if (ids.isEmpty()) return EventByIdResult(TargetLoadState.Idle)
        val remaining = ids.toMutableSet()
        val invalidIds = mutableSetOf<String>()
        var result = EventByIdResult(TargetLoadState.Unavailable(TargetFetchFailure.Unexpected))
        val session = openSession(
            SubscriptionSpec(
                id = "event-by-id-${Random.nextLong().toULong()}",
                filters = listOf(NostrFilter(ids = ids.toList(), limit = ids.size)),
                behavior = SubscriptionBehavior.Fetch(timeoutMillis = 5_000),
                // An invalid first response must not suppress a valid copy from another relay.
                deduplicateEvents = false,
            ),
        )
        try {
            session.signals.takeWhile { signal ->
                when (signal) {
                    is SubscriptionSignal.Event -> {
                        val event = signal.event
                        if (event.id in remaining) {
                            if (validate(event)) {
                                remaining.remove(event.id)
                                invalidIds.remove(event.id)
                                onEvent(event)
                            } else {
                                invalidIds.add(event.id)
                            }
                        }
                    }
                    is SubscriptionSignal.FetchCompleted -> {
                        val missing = when {
                            invalidIds.isNotEmpty() -> TargetLoadState.Unavailable(TargetFetchFailure.InvalidResponse)
                            signal.outcomes.isEmpty() -> TargetLoadState.Unavailable(TargetFetchFailure.NoRelay)
                            signal.timedOut || signal.outcomes.values.any { it != RelayOutcome.Eose } ->
                                TargetLoadState.Unavailable(TargetFetchFailure.Incomplete)
                            else -> TargetLoadState.NotFoundInQueriedRelays
                        }
                        result = EventByIdResult(missing, signal.outcomes)
                        return@takeWhile false
                    }
                    else -> Unit
                }
                remaining.isNotEmpty()
            }.collect { }
        } finally {
            withContext(NonCancellable) { session.close() }
        }
        return result
    }
}
