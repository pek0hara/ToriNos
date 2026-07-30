package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.util.appLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ログインしたアカウントが他クライアントから公開した NIP-65 リレーリストを取得し、
 * ToriNos の端末設定へ反映する。
 */
object RelayListSynchronizer {
    private var syncGeneration = 0L

    suspend fun syncFromRelays(pubkey: String): Boolean {
        RelayStore.isLoaded.first { it }

        val relayUrls = (
            RelayStore.enabledRelayUrlsSnapshot() +
                RelayStore.defaults.map { it.url }
            )
            .mapNotNull(::normalizeRelayUrl)
            .distinct()
        if (relayUrls.isEmpty()) return false

        val generation = ++syncGeneration
        val subscriptionIds = relayUrls.indices.map { index ->
            "relay-list-sync-${pubkey.take(12)}-$generation-$index"
        }
        val latestEventMutex = Mutex()
        var latestEvent: NostrEvent? = null

        try {
            coroutineScope {
                val completedSubscriptions = mutableSetOf<String>()
                val allComplete = CompletableDeferred<Unit>()
                val completionMutex = Mutex()

                val eventJobs = subscriptionIds.map { subscriptionId ->
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        NostrRepository.events(subscriptionId).collect { event ->
                            if (event.kind != RELAY_LIST_KIND || event.pubkey != pubkey) return@collect
                            latestEventMutex.withLock {
                                val current = latestEvent
                                if (current == null ||
                                    event.createdAt > current.createdAt ||
                                    (event.createdAt == current.createdAt && event.id > current.id)
                                ) {
                                    latestEvent = event
                                }
                            }
                        }
                    }
                }
                val eoseJobs = subscriptionIds.map { subscriptionId ->
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        NostrRepository.eose(subscriptionId).first()
                        completionMutex.withLock {
                            completedSubscriptions += subscriptionId
                            if (completedSubscriptions.size == subscriptionIds.size) {
                                allComplete.complete(Unit)
                            }
                        }
                    }
                }

                try {
                    relayUrls.forEachIndexed { index, relayUrl ->
                        NostrRepository.subscribeTemporaryRelay(
                            subscriptionId = subscriptionIds[index],
                            filter = NostrFilter(
                                kinds = listOf(RELAY_LIST_KIND),
                                authors = listOf(pubkey),
                                limit = 1,
                            ),
                            relayUrl = relayUrl,
                        )
                    }
                    withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                        allComplete.await()
                    }
                } finally {
                    eventJobs.forEach { it.cancel() }
                    eoseJobs.forEach { it.cancel() }
                    subscriptionIds.forEach(NostrRepository::closeTemporaryRelay)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            appLog("[RelayListSynchronizer] sync failed: ${e::class.simpleName}: ${e.message}")
            return false
        }

        val publishedUrls = latestEvent
            ?.tags
            ?.mapNotNull { tag ->
                tag.takeIf { it.size >= 2 && it[0] == "r" }
                    ?.get(1)
                    ?.let(::normalizeRelayUrl)
            }
            ?.distinct()
            .orEmpty()
        if (publishedUrls.isEmpty()) return false

        RelayStore.applyPublishedRelayUrls(publishedUrls)
        appLog("[RelayListSynchronizer] applied ${publishedUrls.size} relays for ${pubkey.take(8)}")
        return true
    }

    private const val RELAY_LIST_KIND = 10002
    private const val SYNC_TIMEOUT_MS = 10_000L
}
