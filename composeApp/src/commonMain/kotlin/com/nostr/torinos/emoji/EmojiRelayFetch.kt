package com.nostr.torinos.emoji

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.NostrRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 購読して EOSE（またはタイムアウト）まで待ち、受け取ったイベントを返して購読を閉じる。 */
internal suspend fun fetchEventsUntilEose(
    subscriptionId: String,
    filter: NostrFilter,
    timeoutMs: Long,
    accept: (NostrEvent) -> Boolean,
): List<NostrEvent> = coroutineScope {
    val mutex = Mutex()
    val received = mutableListOf<NostrEvent>()
    // 購読要求より先に受信を始め、最初のイベントを取りこぼさない。
    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        NostrRepository.events(subscriptionId).collect { event ->
            if (accept(event)) mutex.withLock { received += event }
        }
    }
    val eose = async(start = CoroutineStart.UNDISPATCHED) { NostrRepository.eose(subscriptionId).first() }
    try {
        NostrRepository.subscribe(subscriptionId, filter)
        withTimeoutOrNull(timeoutMs) { eose.await() }
        mutex.withLock { received.toList() }
    } finally {
        collector.cancel()
        eose.cancel()
        NostrRepository.close(subscriptionId)
    }
}

/** 置換可能イベントの新旧比較（NIP-01: created_at が同じなら id の小さい方）。 */
internal fun NostrEvent.isNewerThan(other: NostrEvent): Boolean =
    createdAt > other.createdAt || (createdAt == other.createdAt && id < other.id)

internal fun Iterable<NostrEvent>.newestOrNull(): NostrEvent? =
    fold(null as NostrEvent?) { newest, event -> if (newest == null || event.isNewerThan(newest)) event else newest }
