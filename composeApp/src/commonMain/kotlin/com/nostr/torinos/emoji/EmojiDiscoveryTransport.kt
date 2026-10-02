package com.nostr.torinos.emoji

import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

internal data class EmojiDiscoveryFetch(val events: List<NostrEvent>, val complete: Boolean)

/** 実リレーからの検証済み応答だけを返す。キャッシュ再生は今回の取得人数に含めない。 */
internal class EmojiDiscoveryTransport(
    private val open: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    private val validate: (NostrEvent) -> Boolean = ::isValidEvent,
) {
    suspend fun fetch(id: String, relay: String, filters: List<NostrFilter>): EmojiDiscoveryFetch {
        var session: SubscriptionSession? = null
        val events = linkedMapOf<String, NostrEvent>()
        var complete = false
        try {
            session = open(SubscriptionSpec(
                id = id,
                filters = filters,
                target = RelayTarget.Single(relay),
                behavior = SubscriptionBehavior.Fetch(10_000),
                deduplicateEvents = false,
            ))
            session.signals.collect { signal ->
                when (signal) {
                    is SubscriptionSignal.Event -> {
                        val event = signal.event
                        if (signal.relayUrl == relay && event.id !in events &&
                            filters.any { it.acceptsEmojiDiscoveryEvent(event) } && withContext(Dispatchers.Default) { validate(event) }
                        ) events[event.id] = event
                    }
                    is SubscriptionSignal.FetchCompleted -> {
                        complete = !signal.timedOut && signal.outcomes[relay] == RelayOutcome.Eose
                    }
                    else -> Unit
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 部分応答は保持し、正常EOSEとは区別する。
        } finally {
            withContext(NonCancellable) { session?.close() }
        }
        return EmojiDiscoveryFetch(events.values.toList(), complete)
    }
}

internal fun NostrFilter.acceptsEmojiDiscoveryEvent(event: NostrEvent): Boolean =
    (kinds == null || event.kind in kinds) &&
        (authors == null || event.pubkey in authors) &&
        (dTags == null || event.dTag() in dTags)
