package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PublishProgress(
    val attempt: Long,
    val targets: Set<String>,
    val result: RelayPublishResult,
) {
    val pending: Set<String> get() = targets - result.succeededRelays - result.failedRelays.keys
    val complete: Boolean get() = pending.isEmpty()
}

/** 画面を参照せず、同一署名済みeventの配送attemptを集約する。 */
internal class PublishTicket(val event: NostrEvent, val targets: Set<String>) {
    private val lock = SynchronousLock()
    private val mutable = MutableStateFlow(PublishProgress(0, targets, RelayPublishResult(emptySet(), emptyMap())))
    val progress = mutable.asStateFlow()

    fun beginAttempt(): Pair<Long, Set<String>> = lock.withLock {
        val previous = mutable.value
        val remaining = targets - previous.result.succeededRelays
        val next = previous.copy(attempt = previous.attempt + 1,
            result = previous.result.copy(failedRelays = emptyMap(), rejectedRelays = emptySet()))
        mutable.value = next
        next.attempt to remaining
    }

    fun record(attempt: Long, result: RelayPublishResult) = lock.withLock {
        val previous = mutable.value
        val success = previous.result.succeededRelays + (result.succeededRelays intersect targets)
        val failed = if (attempt == previous.attempt) {
            (previous.result.failedRelays + result.failedRelays.filterKeys { it in targets }) - success
        } else previous.result.failedRelays - success
        mutable.value = previous.copy(result = RelayPublishResult(success, failed,
            if (attempt == previous.attempt) (previous.result.rejectedRelays + result.rejectedRelays) - success else previous.result.rejectedRelays - success))
    }
}
