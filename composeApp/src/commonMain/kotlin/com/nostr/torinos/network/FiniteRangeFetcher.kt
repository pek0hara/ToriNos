package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/** 範囲取得の継続はリレー別。短い応答・同秒境界を全体の終端と誤認しない。 */
internal suspend fun fetchFiniteRange(
    spec: SubscriptionSpec,
    open: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    maxPagesPerRelay: Int = 3,
    onEvent: suspend (String, NostrEvent) -> Unit = { _, _ -> },
): FiniteFetchResult {
    require(spec.filters.size == 1)
    require(maxPagesPerRelay > 0)
    val behavior = spec.behavior as SubscriptionBehavior.Fetch
    val started = TimeSource.Monotonic.markNow()
    val original = spec.filters.single()
    val first = fetchFiniteEvents(spec, open, onEvent = onEvent)
    val events = first.eventsById.toMutableMap()
    val sources = first.sourceRelays.mapValues { it.value.toMutableSet() }.toMutableMap()
    val observations = first.relays.toMutableMap()
    var exhaustedBudget = false
    val remainingMillis = (behavior.timeoutMillis - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(1)
    val finished = withTimeoutOrNull(remainingMillis) {
        for ((relay, initial) in first.relays) {
            if (initial.outcome != RelayOutcome.Eose || !initial.limitMayHaveBeenReached) continue
            var until = initial.oldestCreatedAt ?: continue
            var largestPage = initial.receivedIds.size
            val received = initial.receivedIds.toMutableSet()
            var limit = original.limit ?: 100
            var complete = false
            var outcome: RelayOutcome = initial.outcome
            for (pageNumber in 1 until maxPagesPerRelay) {
                val page = fetchFiniteEvents(
                    spec.copy(id = "${spec.id.take(24)}-p$pageNumber-${relay.hashCode()}",
                        target = RelayTarget.Explicit(setOf(relay)), filters = listOf(original.copy(until = until, limit = limit))),
                    open = open, onEvent = onEvent,
                )
                page.eventsById.forEach { (id, event) -> events[id] = event }
                page.sourceRelays.forEach { (id, relays) -> sources.getOrPut(id) { mutableSetOf() }.addAll(relays) }
                val observation = page.relays[relay]
                outcome = observation?.outcome ?: RelayOutcome.TimedOut
                if (outcome != RelayOutcome.Eose) break
                val ids = observation?.receivedIds.orEmpty()
                if (ids.isEmpty()) { complete = true; break }
                val oldest = observation?.oldestCreatedAt ?: break
                received += ids
                when {
                    oldest < until -> until = oldest
                    ids.size < largestPage && ids.size < limit -> {
                        if (until == 0L) { complete = true; break }
                        until-- // より大きなページを返せた観測があり、この境界秒は取り終えている。
                    }
                    else -> {
                        val cap = RelayLimits.maxLimit(relay) ?: (original.limit ?: 100) * 4
                        val next = (limit * 2).coerceAtMost(cap)
                        if (next <= limit) break
                        limit = next
                    }
                }
                largestPage = maxOf(largestPage, ids.size)
            }
            observations[relay] = RelayPageObservation(outcome, received,
                received.mapNotNull { events[it]?.createdAt }.minOrNull(), !complete)
            if (!complete && outcome == RelayOutcome.Eose) exhaustedBudget = true
        }
        true
    }
    return first.copy(
        eventsById = events.toMap(), sourceRelays = sources.mapValues { it.value.toSet() },
        relays = observations,
        stopReason = when {
            finished == null -> FetchStopReason.DeadlineExpired
            exhaustedBudget -> FetchStopReason.PageBudgetReached
            else -> first.stopReason
        },
    )
}
