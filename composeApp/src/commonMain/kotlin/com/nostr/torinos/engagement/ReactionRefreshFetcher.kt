package com.nostr.torinos.engagement

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec

internal object ReactionRefreshFetcher {
    suspend fun fetch(
        subscriptionId: String,
        eventId: String,
        target: RelayTarget = RelayTarget.AllEnabled,
    ): List<NostrEvent> {
        val events = linkedMapOf<String, NostrEvent>()
        val session = NostrRepository.openSubscription(
            SubscriptionSpec(
                id = subscriptionId,
                filters = listOf(NostrFilter(kinds = listOf(7), eTags = listOf(eventId), limit = 500)),
                target = target,
                behavior = SubscriptionBehavior.Fetch(timeoutMillis = 5_000),
            ),
        )
        try {
            session.signals.collect { signal ->
                if (signal is SubscriptionSignal.Event && signal.event.kind == 7) {
                    events[signal.event.id] = signal.event
                }
            }
        } finally {
            session.close()
        }
        return events.values.toList()
    }
}
