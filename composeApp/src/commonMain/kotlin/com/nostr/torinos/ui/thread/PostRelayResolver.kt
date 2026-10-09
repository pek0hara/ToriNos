package com.nostr.torinos.ui.thread

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.network.normalizeRelayUrl
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** 登録リレーは候補として扱い、対象EVENTの受信元だけを所在として報告する。 */
internal class PostRelayResolver(
    private val opener: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    private val observedRelays: (String) -> Set<String> = NostrRepository::observedEventRelays,
    private val viewerRelays: suspend () -> Set<String> = { NostrRepository.targetRelayUrls(RelayTarget.AllEnabled) },
) {
    suspend fun resolve(event: NostrEvent, onFound: (Set<String>) -> Unit): Boolean {
        val found = observedRelays(event.id).toMutableSet()
        onFound(found.toSet())
        var latestRelayList: NostrEvent? = null
        val metadataComplete = fetch(
            NostrFilter(authors = listOf(event.pubkey), kinds = listOf(10002), limit = 1),
            RelayTarget.AllEnabled,
        ) { signal ->
            val candidate = signal.event
            if (candidate.kind == 10002 && candidate.pubkey == event.pubkey) {
                val current = latestRelayList
                if (current == null || candidate.createdAt > current.createdAt ||
                    (candidate.createdAt == current.createdAt && candidate.id < current.id)
                ) latestRelayList = candidate
            }
        }
        val writeRelays = latestRelayList?.let(::postAuthorWriteRelays).orEmpty()
        // 登録リレーの大量指定で詳細画面が過剰に接続しないよう、著者側は16件まで確認する。
        val probeTarget = RelayTarget.Explicit(
            viewerRelays() + writeRelays.take(16),
        )
        val postComplete = fetch(
            NostrFilter(ids = listOf(event.id), limit = 1),
            probeTarget,
        ) { signal ->
            if (signal.event.id == event.id) {
                // キャッシュ再配信には架空の受信元が付くため、ネットワークの受信元だけを追加する。
                normalizeRelayUrl(signal.relayUrl)?.let(found::add)
                found += observedRelays(event.id)
                onFound(found.toSet())
            }
        }
        return metadataComplete && postComplete && writeRelays.size <= 16
    }

    private suspend fun fetch(
        filter: NostrFilter,
        target: RelayTarget,
        onEvent: (SubscriptionSignal.Event) -> Unit,
    ): Boolean {
        val session = opener(
            SubscriptionSpec(
                id = "post-relays-${Random.nextLong()}",
                filters = listOf(filter),
                target = target,
                behavior = SubscriptionBehavior.Fetch(timeoutMillis = 8_000L),
                deduplicateEvents = false,
            ),
        )
        var complete = false
        try {
            session.signals.takeWhile { signal ->
                when (signal) {
                    is SubscriptionSignal.Event -> onEvent(signal)
                    is SubscriptionSignal.FetchCompleted -> complete = !signal.timedOut &&
                        signal.outcomes.isNotEmpty() &&
                        signal.outcomes.values.all { it is RelayOutcome.Eose }
                    else -> Unit
                }
                signal !is SubscriptionSignal.FetchCompleted
            }.collect { }
        } finally {
            withContext(NonCancellable) { session.close() }
        }
        return complete
    }
}

internal fun postAuthorWriteRelays(event: NostrEvent): List<String> = event.tags.mapNotNull { tag ->
    if (tag.firstOrNull() != "r") return@mapNotNull null
    val marker = tag.getOrNull(2)
    if (marker != null && marker != "write") return@mapNotNull null
    tag.getOrNull(1)?.let(::normalizeRelayUrl)
}.distinct()
