package com.nostr.torinos.network

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.loadPublicKey
import com.nostr.torinos.crypto.signEvent
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
import kotlin.time.Clock

/**
 * ログインしたアカウントが他クライアントから公開した NIP-65 リレーリストを取得し、
 * ToriNos の端末設定へ反映する。
 */
object RelayListSynchronizer {
    private var syncGeneration = 0L
    private val operationMutex = Mutex()

    suspend fun syncFromRelays(pubkey: String): Boolean = operationMutex.withLock {
        RelayStore.isLoaded.first { it }

        val latestEvent = fetchLatestEvent(pubkey) ?: return@withLock false
        val publishedUrls = relayUrlsFromTags(latestEvent.tags)
        if (publishedUrls.isEmpty()) return@withLock false

        RelayStore.applyPublishedRelayUrls(publishedUrls)
        appLog("[RelayListSynchronizer] applied ${publishedUrls.size} relays for ${pubkey.take(8)}")
        true
    }

    /** 公開直前に最新の kind:10002 を取得し、明示された追加・削除だけを反映する。 */
    suspend fun updatePublishedRelayList(
        additions: Set<String>,
        removals: Set<String>,
        requireExistingEvent: Boolean,
    ): RelayListUpdateResult = operationMutex.withLock {
        val privateKeyHex = KeyStorage.loadPrivateKey() ?: error("秘密鍵がありません")
        val pubkey = loadPublicKey() ?: error("公開鍵を取得できません")
        val latestEvent = fetchLatestEvent(pubkey)
        checkExistingPublishedRelayListWasFetched(
            requireExistingEvent = requireExistingEvent,
            latestEventExists = latestEvent != null,
        )
        val updatedTags = applyRelayListChanges(
            currentTags = latestEvent?.tags.orEmpty(),
            additions = additions,
            removals = removals,
        )
        val updatedRelayUrls = relayUrlsFromTags(updatedTags)
        val targetRelayUrls = (
            RelayStore.enabledRelayUrlsSnapshot() +
                RelayStore.defaults.map { it.url } +
                relayUrlsFromTags(latestEvent?.tags.orEmpty()) +
                updatedRelayUrls
            )
            .mapNotNull(::normalizeRelayUrl)
            .distinct()
        check(targetRelayUrls.isNotEmpty()) { "送信先リレーがありません" }

        val event = signEvent(
            privateKeyHex = privateKeyHex,
            content = "",
            kind = RELAY_LIST_KIND,
            tags = updatedTags,
            createdAt = maxOf(
                Clock.System.now().epochSeconds,
                (latestEvent?.createdAt ?: -1L) + 1L,
            ),
        )
        val publishResult = NostrRepository.publishToRelaysWithResult(event, targetRelayUrls)
        check(publishResult.succeededRelays.isNotEmpty()) { "すべてのリレーへの送信に失敗しました" }
        RelayListUpdateResult(event = event, publishResult = publishResult)
    }

    /** UI 表示用。取得できなかった場合は例外にし、空の一覧との誤認を防ぐ。 */
    suspend fun fetchPublishedRelayList(
        onFirstResponse: () -> Unit = {},
    ): PublishedRelayListSnapshot = operationMutex.withLock {
        val pubkey = loadPublicKey()
            ?: return@withLock PublishedRelayListSnapshot(urls = emptySet(), hasPublishedEvent = false)
        val event = fetchLatestEvent(pubkey, onFirstResponse)
        PublishedRelayListSnapshot(
            urls = relayUrlsFromTags(event?.tags.orEmpty()).toSet(),
            hasPublishedEvent = event != null,
        )
    }

    private suspend fun fetchLatestEvent(
        pubkey: String,
        onFirstResponse: () -> Unit = {},
    ): NostrEvent? {
        RelayStore.isLoaded.first { it }
        val relayUrls = (
            RelayStore.enabledRelayUrlsSnapshot() +
                RelayStore.defaults.map { it.url }
            )
            .mapNotNull(::normalizeRelayUrl)
            .distinct()
        check(relayUrls.isNotEmpty()) { "取得先リレーがありません" }

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
                val firstResponseMutex = Mutex()
                var firstResponseNotified = false

                suspend fun notifyFirstResponse() {
                    val shouldNotify = firstResponseMutex.withLock {
                        if (firstResponseNotified) {
                            false
                        } else {
                            firstResponseNotified = true
                            true
                        }
                    }
                    if (shouldNotify) onFirstResponse()
                }

                val eventJobs = subscriptionIds.map { subscriptionId ->
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        NostrRepository.events(subscriptionId).collect { event ->
                            if (event.kind != RELAY_LIST_KIND || event.pubkey != pubkey) return@collect
                            notifyFirstResponse()
                            latestEventMutex.withLock {
                                val current = latestEvent
                                if (current == null ||
                                    event.createdAt > current.createdAt ||
                                    (event.createdAt == current.createdAt && event.id < current.id)
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
                        notifyFirstResponse()
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
                    val completed = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                        allComplete.await()
                        true
                    }
                    val hasRelayResponse = completionMutex.withLock {
                        completedSubscriptions.isNotEmpty()
                    } || latestEventMutex.withLock {
                        latestEvent != null
                    }
                    if (completed != true && !hasRelayResponse) {
                        error("リレーリストの取得がタイムアウトしました")
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
            throw e
        }
        return latestEvent
    }

    private const val RELAY_LIST_KIND = 10002
    private const val SYNC_TIMEOUT_MS = 5_000L
}

data class RelayListUpdateResult(
    val event: NostrEvent,
    val publishResult: RelayPublishResult,
)

data class PublishedRelayListSnapshot(
    val urls: Set<String>,
    val hasPublishedEvent: Boolean,
)

internal fun checkExistingPublishedRelayListWasFetched(
    requireExistingEvent: Boolean,
    latestEventExists: Boolean,
) {
    check(!requireExistingEvent || latestEventExists) {
        "既存の公開リレーリストを再取得できなかったため、更新を中止しました"
    }
}

internal fun relayUrlsFromTags(tags: List<List<String>>): List<String> =
    tags.mapNotNull { tag ->
        tag.takeIf { it.size >= 2 && it[0] == "r" }
            ?.get(1)
            ?.let(::normalizeRelayUrl)
    }.distinct()

/** 既存タグを保ったまま、ユーザーが明示した URL の追加・削除だけを適用する。 */
internal fun applyRelayListChanges(
    currentTags: List<List<String>>,
    additions: Set<String>,
    removals: Set<String>,
): List<List<String>> {
    val normalizedRemovals = removals.mapNotNull(::normalizeRelayUrl).toSet()
    val normalizedAdditions = additions
        .mapNotNull(::normalizeRelayUrl)
        .filterNot { it in normalizedRemovals }
        .distinct()
    val retainedTags = currentTags.filterNot { tag ->
        tag.size >= 2 && tag[0] == "r" && normalizeRelayUrl(tag[1]) in normalizedRemovals
    }
    val retainedRelayUrls = relayUrlsFromTags(retainedTags).toSet()
    return retainedTags + normalizedAdditions
        .filterNot { it in retainedRelayUrls }
        .map { listOf("r", it) }
}
