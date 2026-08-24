package com.nostr.torinos.ui.timeline

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach

internal data class QuoteResolution(
    val events: Map<String, NostrEvent>,
    val missingIds: Set<String>,
)

/** 引用イベントの有限取得。購読はこのResolverが単独所有し、必ずcloseする。 */
internal class QuoteResolver(
    private val idPrefix: String,
    private val opener: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
) {
    private var sequence = 0L

    suspend fun resolve(
        eventIds: Set<String>,
        kinds: List<Int>,
        target: RelayTarget = RelayTarget.AllEnabled,
    ): QuoteResolution {
        if (eventIds.isEmpty()) return QuoteResolution(emptyMap(), emptySet())
        val events = linkedMapOf<String, NostrEvent>()
        val session = opener(
            SubscriptionSpec(
                id = "$idPrefix-${++sequence}",
                filters = listOf(NostrFilter(ids = eventIds.toList(), kinds = kinds)),
                target = target,
                behavior = SubscriptionBehavior.Fetch(),
            ),
        )
        try {
            session.signals
                .onEach { signal ->
                    if (signal is SubscriptionSignal.Event && signal.event.id in eventIds) {
                        events[signal.event.id] = signal.event
                    }
                }
                .first { it is SubscriptionSignal.FetchCompleted }
        } finally {
            session.close()
        }
        return QuoteResolution(events, eventIds - events.keys)
    }
}
