package com.nostr.torinos.network

import com.nostr.torinos.util.refactorTraceLog
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.util.appLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 最初の受理と残りの配送を分ける。開始済み配送は注入されたRepository scopeで継続する。 */
internal class RelayPublishCoordinator(
    private val scope: CoroutineScope,
    private val send: suspend (NostrEvent, Collection<String>, Boolean, Boolean) -> RelayPublishResult,
) {
    suspend fun untilFirstSuccess(
        event: NostrEvent,
        relayUrls: Collection<String>,
        onRelayResult: suspend (RelayPublishResult) -> Unit = {},
        respectRelayWritePolicy: Boolean = true,
        awaitAcceptance: Boolean = false,
    ): RelayPublishResult {
        val targets = relayUrls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return RelayPublishResult(emptySet(), emptyMap())

        val trace = com.nostr.torinos.util.nextRefactorTraceId()
        refactorTraceLog("Publish") { "start trace=$trace kind=${event.kind} event=${event.id.take(12)} targets=${targets.size}" }
        val completion = CompletableDeferred<RelayPublishResult>()
        val resultMutex = Mutex()
        val failures = linkedMapOf<String, String>()
        val rejected = linkedSetOf<String>()
        var completedCount = 0

        targets.forEach { relayUrl ->
            scope.launch {
                val result = try { send(event, listOf(relayUrl), awaitAcceptance, respectRelayWritePolicy) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) {
                    RelayPublishResult(emptySet(), mapOf(relayUrl to (error.message ?: "送信に失敗しました")))
                }
                refactorTraceLog("Publish") { "relay-result trace=$trace success=${result.successCount} failed=${result.failureCount} rejected=${result.rejectedRelays.size}" }
                try { onRelayResult(result) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) {
                    appLog("[Repo] publish result callback failed: ${error::class.simpleName}: ${error.message}")
                }
                val completedResult = resultMutex.withLock {
                    completedCount++
                    failures.putAll(result.failedRelays)
                    rejected += result.rejectedRelays
                    when {
                        result.succeededRelays.isNotEmpty() -> RelayPublishResult(
                            succeededRelays = result.succeededRelays,
                            failedRelays = failures.toMap(),
                            rejectedRelays = rejected.toSet(),
                        )
                        completedCount == targets.size -> RelayPublishResult(
                            succeededRelays = emptySet(),
                            failedRelays = failures.toMap(),
                            rejectedRelays = rejected.toSet(),
                        )
                        else -> null
                    }
                }
                if (completedResult != null) completion.complete(completedResult)
            }.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    refactorTraceLog("Publish") { "cancelled trace=$trace" }
                    completion.cancel(cause)
                }
            }
        }

        return completion.await().also { result ->
            refactorTraceLog("Publish") { "returned trace=$trace success=${result.successCount} failed=${result.failureCount} rejected=${result.rejectedRelays.size}" }
        }
    }
}
