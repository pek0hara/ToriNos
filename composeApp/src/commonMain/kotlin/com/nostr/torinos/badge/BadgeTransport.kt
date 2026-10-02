package com.nostr.torinos.badge

import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.random.Random

/** Events received from one relay; used to page each relay independently. */
internal data class BadgeRelayPage(val count: Int, val oldest: Long)

/**
 * [complete] means every target relay answered EOSE. [answered] lists the relays that did, so a
 * partial response can still be told apart from a total failure.
 */
internal data class BadgeFetchResult(
    val events: List<NostrEvent>,
    val complete: Boolean,
    val answered: Set<String> = emptySet(),
    val pages: Map<String, BadgeRelayPage> = emptyMap(),
)

internal class BadgeTransport(
    private val open: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    private val validate: (NostrEvent) -> Boolean = ::isValidEvent,
    private val send: suspend (NostrEvent, Set<String>) -> RelayPublishResult = { event, urls ->
        NostrRepository.publishToRelaysWithResult(event, urls, awaitAcceptance = true)
    },
    private val validationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun fetch(filters: List<NostrFilter>, relays: Set<String>, onEvent: (NostrEvent) -> Unit = {}): BadgeFetchResult {
        if (filters.isEmpty()) return BadgeFetchResult(emptyList(), true)
        if (relays.isEmpty()) return BadgeFetchResult(emptyList(), false)
        val events = linkedMapOf<String, NostrEvent>()
        val pages = mutableMapOf<String, BadgeRelayPage>()
        val seen = mutableMapOf<String, MutableSet<String>>()
        var complete = false
        var answered = emptySet<String>()
        var truncated = false
        var session: SubscriptionSession? = null
        try {
            val finished = withTimeoutOrNull(12_000) {
                session = open(SubscriptionSpec(
                    id = "badge-${Random.nextInt().toUInt()}", filters = filters,
                    target = RelayTarget.Explicit(relays), behavior = SubscriptionBehavior.Fetch(10_000),
                    deduplicateEvents = false,
                ))
                session!!.signals.collect { signal ->
                    when (signal) {
                        is SubscriptionSignal.Event -> {
                            val event = signal.event
                            if (signal.relayUrl !in relays) return@collect
                            // Count re-deliveries per relay without validating the same event twice.
                            val known = events[event.id] == event || (filters.any { matchesBadgeFilter(event, it) } &&
                                withContext(validationDispatcher) { validate(event) } && event.id !in events && run {
                                    if (events.size >= 10_000) { truncated = true; false }
                                    else { events[event.id] = event; onEvent(event); true }
                                })
                            if (known && seen.getOrPut(signal.relayUrl) { mutableSetOf() }.add(event.id)) {
                                val page = pages[signal.relayUrl]
                                pages[signal.relayUrl] = BadgeRelayPage((page?.count ?: 0) + 1, minOf(page?.oldest ?: event.createdAt, event.createdAt))
                            }
                        }
                        is SubscriptionSignal.FetchCompleted -> {
                            answered = signal.outcomes.filterValues { it is RelayOutcome.Eose }.keys
                            complete = !signal.timedOut && signal.outcomes.keys.containsAll(relays) &&
                                signal.outcomes.values.all { it is RelayOutcome.Eose }
                        }
                        else -> Unit
                    }
                }
                true
            }
            if (finished == null || truncated) complete = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            complete = false
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { session?.close() }
        }
        return BadgeFetchResult(events.values.toList(), complete, answered, pages)
    }

    suspend fun publish(event: NostrEvent, relays: Set<String>): RelayPublishResult = send(event, relays)

    suspend fun live(pubkey: String, relays: Set<String>, receive: suspend (NostrEvent) -> Unit) {
        if (relays.isEmpty()) return
        val session = open(SubscriptionSpec(
            id = "badge-live-${Random.nextInt().toUInt()}",
            filters = listOf(NostrFilter(authors = listOf(pubkey), kinds = listOf(10008))),
            target = RelayTarget.Explicit(relays), deduplicateEvents = false,
        ))
        try {
            session.signals.collect { signal ->
                if (signal is SubscriptionSignal.Event && signal.event.pubkey == pubkey &&
                    signal.event.kind == 10008 && validate(signal.event)) receive(signal.event)
            }
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { session.close() }
        }
    }
}

internal fun matchesBadgeFilter(event: NostrEvent, filter: NostrFilter): Boolean {
    fun tag(key: String, values: List<String>?) = values == null || event.tags.any { it.firstOrNull() == key && it.getOrNull(1) in values }
    return (filter.ids == null || event.id in filter.ids) && (filter.authors == null || event.pubkey in filter.authors) &&
        (filter.kinds == null || event.kind in filter.kinds) && (filter.since == null || event.createdAt >= filter.since) &&
        (filter.until == null || event.createdAt <= filter.until) && tag("d", filter.dTags) && tag("p", filter.pTags) &&
        tag("a", filter.aTags) && tag("e", filter.eTags)
}
