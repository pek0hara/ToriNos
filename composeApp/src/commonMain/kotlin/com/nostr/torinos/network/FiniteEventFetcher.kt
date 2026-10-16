package com.nostr.torinos.network

import com.nostr.torinos.util.refactorTraceLog
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.matches
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class FetchStopReason {
    AllRelaysFinished, SettleExpired, DeadlineExpired, EventBudgetReached,
    RequestedIdsResolved, PageBudgetReached, NoTargets, UnexpectedEnd,
}

internal data class RelayPageObservation(
    val outcome: RelayOutcome,
    val receivedIds: Set<String>,
    val oldestCreatedAt: Long?,
    val limitMayHaveBeenReached: Boolean,
)

/** ページ応答の成功と、期間全体のcoverageは別。EOSEだけで期間取得済みにしない。 */
internal data class FiniteFetchResult(
    val expectedRelays: Set<String>,
    val eventsById: Map<String, NostrEvent>,
    val sourceRelays: Map<String, Set<String>>,
    val relays: Map<String, RelayPageObservation>,
    val stopReason: FetchStopReason,
) {
    val events: List<NostrEvent> get() = eventsById.values.toList()
    val allAnswered: Boolean get() = expectedRelays.isNotEmpty() &&
        expectedRelays.all { relays[it]?.outcome == RelayOutcome.Eose } &&
        stopReason in setOf(FetchStopReason.AllRelaysFinished, FetchStopReason.SettleExpired)
    val anyAnswered: Boolean get() = relays.values.any { it.outcome == RelayOutcome.Eose }
    val rangeComplete: Boolean get() = allAnswered && relays.values.none { it.limitMayHaveBeenReached }
}

/** 単発取得の所有者。即時応答、観測元、部分失敗を保持し、必ず購読を閉じる。 */
internal suspend fun fetchFiniteEvents(
    spec: SubscriptionSpec,
    open: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    settleAfterEoseMillis: Long? = null,
    requestedIds: Set<String>? = null,
    maxAcceptedEvents: Int = 10_000,
    onEvent: suspend (String, NostrEvent) -> Unit = { _, _ -> },
): FiniteFetchResult {
    val behavior = spec.behavior as? SubscriptionBehavior.Fetch
        ?: error("有限取得にはFetchを指定してください")
    require(maxAcceptedEvents > 0)
    require(settleAfterEoseMillis == null || settleAfterEoseMillis > 0)
    require(requestedIds == null || requestedIds.isNotEmpty())
    val trace = com.nostr.torinos.util.nextRefactorTraceId()
    refactorTraceLog("Fetch") { "start trace=$trace filters=${spec.filters.size} timeoutMs=${behavior.timeoutMillis}" }
    val events = linkedMapOf<String, NostrEvent>()
    val sources = mutableMapOf<String, MutableSet<String>>()
    val byRelay = mutableMapOf<String, MutableSet<String>>()
    val outcomes = mutableMapOf<String, RelayOutcome>()
    var expected = emptySet<String>()
    var reason: FetchStopReason? = null
    var session: SubscriptionSession? = null
    try {
        val finished = withTimeoutOrNull(behavior.timeoutMillis) {
            coroutineScope {
                val opened = open(spec.copy(deduplicateEvents = false, replayCachedEvents = false))
                session = opened
                expected = opened.expectedRelayUrls.toSet()
                var settleJob: Job? = null
                try {
                    opened.signals.firstOrNull { signal ->
                        when (signal) {
                            is SubscriptionSignal.Event -> {
                                val event = signal.event
                                if (spec.filters.any { it.matches(event) } &&
                                    (event.id in events || events.size < maxAcceptedEvents)
                                ) {
                                    events[event.id] = event
                                    sources.getOrPut(event.id) { linkedSetOf() }.add(signal.relayUrl)
                                    byRelay.getOrPut(signal.relayUrl) { linkedSetOf() }.add(event.id)
                                    onEvent(signal.relayUrl, event)
                                    if (reason == null && requestedIds != null && events.keys.containsAll(requestedIds)) {
                                        reason = FetchStopReason.RequestedIdsResolved
                                        opened.finishPendingRelays()
                                    } else if (reason == null && events.size >= maxAcceptedEvents) {
                                        reason = FetchStopReason.EventBudgetReached
                                        opened.finishPendingRelays()
                                    }
                                }
                                reason == FetchStopReason.RequestedIdsResolved || reason == FetchStopReason.EventBudgetReached
                            }
                            is SubscriptionSignal.Eose -> {
                                outcomes[signal.relayUrl] = RelayOutcome.Eose
                                if (settleAfterEoseMillis != null) {
                                    settleJob?.cancel()
                                    settleJob = launch {
                                        delay(settleAfterEoseMillis)
                                        if (reason == null) reason = FetchStopReason.SettleExpired
                                        opened.finishPendingRelays()
                                    }
                                }
                                false
                            }
                            is SubscriptionSignal.FetchCompleted -> {
                                outcomes.putAll(signal.outcomes)
                                if (expected.isEmpty()) expected = signal.outcomes.keys.toSet()
                                if (reason == null) reason = when {
                                    expected.isEmpty() -> FetchStopReason.NoTargets
                                    signal.timedOut -> FetchStopReason.DeadlineExpired
                                    else -> FetchStopReason.AllRelaysFinished
                                }
                                true
                            }
                            is SubscriptionSignal.Closed -> {
                                outcomes[signal.relayUrl] = RelayOutcome.Closed(signal.reason, signal.retry)
                                false
                            }
                            is SubscriptionSignal.RelayUnavailable -> {
                                outcomes[signal.relayUrl] = RelayOutcome.Unavailable(signal.reason)
                                false
                            }
                            else -> false
                        }
                    }
                    if (reason == null) reason = FetchStopReason.UnexpectedEnd
                } finally {
                    settleJob?.cancel()
                }
            }
            true
        }
        if (finished == null && reason == null) reason = FetchStopReason.DeadlineExpired
    } finally {
        withContext(NonCancellable) { session?.close() }
        refactorTraceLog("Fetch") { "closed trace=$trace reason=${reason ?: "cancelled-or-error"}" }
    }
    val targets = expected.ifEmpty { outcomes.keys + byRelay.keys }
    val result = FiniteFetchResult(
        expectedRelays = targets,
        eventsById = events.toMap(),
        sourceRelays = sources.mapValues { it.value.toSet() },
        relays = targets.associateWith { url ->
            val ids = byRelay[url].orEmpty()
            val limit = spec.filters.singleOrNull()?.limit
            val effectiveLimit = limit?.let { minOf(it, RelayLimits.maxLimit(url) ?: it) }
            RelayPageObservation(
                outcome = outcomes[url] ?: RelayOutcome.TimedOut,
                receivedIds = ids.toSet(),
                oldestCreatedAt = ids.mapNotNull { events[it]?.createdAt }.minOrNull(),
                // 複合フィルターは各上限を証明できないのでcoverageを確定しない。
                limitMayHaveBeenReached = spec.filters.size != 1 ||
                    (effectiveLimit != null && ids.size >= effectiveLimit) ||
                    (limit != null && ids.isNotEmpty() && RelayLimits.maxLimit(url) == null),
            )
        },
        stopReason = reason ?: FetchStopReason.UnexpectedEnd,
    )
    refactorTraceLog("Fetch") { "end trace=$trace reason=${result.stopReason} events=${events.size} targets=${targets.size} answered=${outcomes.values.count { it == RelayOutcome.Eose }} allAnswered=${result.allAnswered} rangeComplete=${result.rangeComplete}" }
    return result
}
