package com.nostr.torinos.ui.channel

import com.nostr.torinos.util.refactorTraceLog
import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.network.*
import com.nostr.torinos.ui.timeline.SignedEventPublisher
import com.nostr.torinos.ui.timeline.SignedPublishResult
import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 署名と再送の所有者。画面callbackは呼び出し側scopeの監視からだけ実行する。 */
internal class ChannelPublicationService(
    private val scope: CoroutineScope,
    signer: AccountSigner?,
    private val publisher: SignedEventPublisher = SignedEventPublisher(signer),
    private val canStart: () -> Boolean = { true },
    private val writableRelays: () -> Set<String> = { RelayStore.writableRelayUrlsSnapshot().toSet() },
) {
    private data class Request(val dialog: String, val content: String, val kind: Int, val tags: List<List<String>>, val targets: Set<String>)
    private class Operation(val request: Request, val ticket: PublishTicket) {
        var job: Deferred<SignedPublishResult>? = null
        var released = false
    }
    private val mutex = Mutex()
    private val operations = linkedMapOf<Request, Operation>()

    suspend fun publish(
        dialog: String,
        content: String,
        kind: Int,
        tags: List<List<String>>,
        relayUrls: Collection<String>?,
        observerScope: CoroutineScope,
        validate: () -> Unit = {},
        onEventRelayResult: suspend (NostrEvent, RelayPublishResult, Set<String>) -> Unit = { _, _, _ -> },
    ): SignedPublishResult {
        if (publisher.signerPubkey == null) return SignedPublishResult.MissingSigner
        if (!canStart()) return SignedPublishResult.Failed(IllegalStateException("アカウントが切り替わりました"))
        val request = Request(dialog, content, kind, tags.map { it.toList() },
            normalizeRelayUrls(relayUrls ?: writableRelays(), limit = Int.MAX_VALUE).toSet())
        if (request.targets.isEmpty()) return SignedPublishResult.Failed(IllegalStateException("送信先リレーがありません"))
        val operation = mutex.withLock {
            if (!canStart()) return SignedPublishResult.Failed(IllegalStateException("アカウントが切り替わりました"))
            try { validate() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalStateException) {
                refactorTraceLog("ChannelPublish") { "validation-rejected kind=$kind" }
                return SignedPublishResult.Failed(error)
            }
            operations.entries.removeAll { it.value.released && it.value.ticket.progress.value.complete }
            operations.values.firstOrNull { it.request.copy(targets = request.targets) == request } ?: run {
                if (operations.size >= 64) return SignedPublishResult.Failed(IllegalStateException("送信中の処理が多いため、完了後に再試行してください"))
                val event = publisher.prepare(content, kind, tags).getOrElse { return SignedPublishResult.Failed(it) }
                refactorTraceLog("ChannelPublish") { "prepared kind=$kind event=${event.id.take(12)} targets=${request.targets.size}" }
                Operation(request, PublishTicket(event, request.targets)).also { operations[request] = it }
            }
        }
        val job = mutex.withLock {
            if (!canStart()) return SignedPublishResult.Failed(IllegalStateException("アカウントが切り替わりました"))
            try { validate() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalStateException) {
                refactorTraceLog("ChannelPublish") { "validation-rejected kind=$kind" }
                return SignedPublishResult.Failed(error)
            }
            val progress = operation.ticket.progress.value
            if (operation.job?.isActive == true || (!progress.complete && progress.attempt > 0)) {
                refactorTraceLog("ChannelPublish") { "reuse-inflight kind=$kind event=${operation.ticket.event.id.take(12)} attempt=${progress.attempt}" }
                operation.job!!
            } else if (progress.result.succeededRelays == operation.request.targets) {
                CompletableDeferred<SignedPublishResult>(SignedPublishResult.Published(operation.ticket.event, progress.result))
            } else {
                val (attempt, remaining) = operation.ticket.beginAttempt()
                refactorTraceLog("ChannelPublish") { "attempt kind=$kind event=${operation.ticket.event.id.take(12)} attempt=$attempt remaining=${remaining.size}" }
                scope.async(start = CoroutineStart.LAZY) {
                    val result = publisher.publishPrepared(operation.ticket.event, remaining,
                        onEventRelayResult = { _, result -> operation.ticket.record(attempt, result) })
                    val relayResult = when (result) {
                        is SignedPublishResult.Published -> result.relayResult
                        is SignedPublishResult.Failed -> result.relayResult ?: RelayPublishResult(emptySet(), remaining.associateWith { result.cause.message ?: "送信に失敗しました" })
                        SignedPublishResult.MissingSigner -> RelayPublishResult(emptySet(), remaining.associateWith { "秘密鍵がありません" })
                    }
                    operation.ticket.record(attempt, relayResult)
                    if (operation.ticket.progress.value.result.succeededRelays.isNotEmpty()) {
                        SignedPublishResult.Published(operation.ticket.event, operation.ticket.progress.value.result)
                    } else result
                }.also { operation.job = it; it.start() }
            }
        }
        val deliveryMutex = Mutex()
        var lastDelivered: RelayPublishResult? = null
        suspend fun deliver(progress: PublishProgress) = deliveryMutex.withLock {
            if (canStart() && progress.result != lastDelivered &&
                (progress.result.succeededRelays.isNotEmpty() || progress.result.failedRelays.isNotEmpty())) {
                refactorTraceLog("ChannelPublish") { "ui-result kind=$kind event=${operation.ticket.event.id.take(12)} attempt=${progress.attempt} success=${progress.result.successCount} failed=${progress.result.failureCount} pending=${progress.pending.size}" }
                onEventRelayResult(operation.ticket.event, progress.result, operation.ticket.targets)
                lastDelivered = progress.result
            }
        }
        observerScope.launch {
            operation.ticket.progress.first { progress ->
                deliver(progress)
                progress.complete
            }
        }
        val result = job.await()
        deliver(operation.ticket.progress.value)
        return result
    }

    suspend fun releaseDialog(dialog: String) = mutex.withLock {
        operations.values.filter { it.request.dialog == dialog }.forEach { it.released = true }
        operations.entries.removeAll { it.value.released && it.value.ticket.progress.value.complete }
    }
}
