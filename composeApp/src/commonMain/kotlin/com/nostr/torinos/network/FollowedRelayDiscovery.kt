package com.nostr.torinos.network

import com.nostr.torinos.crypto.loadPublicKey
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

sealed interface FollowedRelayDiscoveryResult {
    data class Completed(val addedCount: Int) : FollowedRelayDiscoveryResult
    data object NothingToFetch : FollowedRelayDiscoveryResult
}

/**
 * フォロー先が公開している NIP-65 リレーリストを取得し、
 * 未登録の URL をリレー設定へ無効状態で追加する。
 */
object FollowedRelayDiscovery {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var generation = 0L

    suspend fun discover(forceRefresh: Boolean = false): FollowedRelayDiscoveryResult =
        mutex.withLock {
            RelayStore.isLoaded.first { it }
            check(withTimeoutOrNull(FOLLOW_LOAD_TIMEOUT_MS) {
                FollowRepository.loaded.first { it }
            } != null) {
                "フォロー一覧を取得できませんでした"
            }

            val ownPubkey = loadPublicKey() ?: return@withLock FollowedRelayDiscoveryResult.NothingToFetch
            val followedPubkeys = FollowRepository.followedPubkeys.value
            if (followedPubkeys.isEmpty()) {
                return@withLock FollowedRelayDiscoveryResult.NothingToFetch
            }

            val now = Clock.System.now().epochSeconds
            val cache = loadCache(ownPubkey)
            val targets = if (forceRefresh) {
                followedPubkeys
            } else {
                followedPubkeys.filterTo(linkedSetOf()) { pubkey ->
                    val lastFetchedAt = cache.fetchedAtByPubkey[pubkey] ?: return@filterTo true
                    now - lastFetchedAt >= CACHE_TTL_SECONDS
                }
            }
            if (targets.isEmpty()) {
                return@withLock FollowedRelayDiscoveryResult.NothingToFetch
            }

            val relayUrls = NostrRepository.targetRelayUrls(RelayTarget.AllEnabled)
            check(relayUrls.isNotEmpty()) { "有効なリレーがありません" }

            val fetchResult = fetchRelayLists(
                pubkeys = targets.sorted(),
                expectedRelayUrls = relayUrls,
            )
            check(fetchResult.completedPubkeys.isNotEmpty()) {
                "フォロー先のリレー情報を取得できませんでした"
            }

            val addedCount = RelayStore.addDiscoveredRelayUrls(fetchResult.discoveredRelayUrls)
            val nextFetchedAt = cache.fetchedAtByPubkey
                .filterKeys { it in followedPubkeys }
                .toMutableMap()
                .apply {
                    fetchResult.completedPubkeys.forEach { pubkey -> put(pubkey, now) }
                }
            saveCache(ownPubkey, DiscoveryCache(fetchedAtByPubkey = nextFetchedAt))
            FollowedRelayDiscoveryResult.Completed(addedCount)
        }

    private suspend fun fetchRelayLists(
        pubkeys: List<String>,
        expectedRelayUrls: Set<String>,
    ): FetchResult = coroutineScope {
        val requestGeneration = ++generation
        val batches = pubkeys.chunked(AUTHOR_BATCH_SIZE)
        val subscriptionIds = batches.indices.map { index ->
            "followed-relays-$requestGeneration-$index"
        }
        val latestEvents = mutableMapOf<String, NostrEvent>()
        val latestEventsMutex = Mutex()
        val completedRelays = subscriptionIds.associateWith { mutableSetOf<String>() }.toMutableMap()
        val completionMutex = Mutex()
        val allComplete = CompletableDeferred<Unit>()

        val eventJobs = subscriptionIds.map { subscriptionId ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                NostrRepository.events(subscriptionId).collect { event ->
                    if (event.kind != RELAY_LIST_KIND) return@collect
                    latestEventsMutex.withLock {
                        val current = latestEvents[event.pubkey]
                        if (current == null ||
                            event.createdAt > current.createdAt ||
                            (event.createdAt == current.createdAt && event.id > current.id)
                        ) {
                            latestEvents[event.pubkey] = event
                        }
                    }
                }
            }
        }
        val eoseJobs = subscriptionIds.map { subscriptionId ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                NostrRepository.eoseRelays(subscriptionId).collect { relayUrl ->
                    completionMutex.withLock {
                        completedRelays.getValue(subscriptionId) += relayUrl
                        if (completedRelays.values.all { it.containsAll(expectedRelayUrls) }) {
                            allComplete.complete(Unit)
                        }
                    }
                }
            }
        }

        try {
            batches.forEachIndexed { index, authors ->
                NostrRepository.subscribe(
                    subscriptionId = subscriptionIds[index],
                    filter = NostrFilter(
                        kinds = listOf(RELAY_LIST_KIND),
                        authors = authors,
                        limit = authors.size,
                    ),
                )
            }
            withTimeoutOrNull(FETCH_TIMEOUT_MS) { allComplete.await() }
        } finally {
            eventJobs.forEach { it.cancel() }
            eoseJobs.forEach { it.cancel() }
            subscriptionIds.forEach { NostrRepository.closeSuspending(it) }
        }

        val respondedSubscriptionIds = completionMutex.withLock {
            completedRelays.filterValues { it.isNotEmpty() }.keys
        }
        val completedPubkeys = batches
            .filterIndexed { index, _ -> subscriptionIds[index] in respondedSubscriptionIds }
            .flatten()
            .toSet()
        val discoveredRelayUrls = latestEventsMutex.withLock {
            latestEvents.values
                .flatMap { event ->
                    event.tags.mapNotNull { tag ->
                        tag.takeIf { it.size >= 2 && it[0] == "r" }
                            ?.get(1)
                            ?.let(::normalizeRelayUrl)
                    }
                }
                .distinct()
        }
        FetchResult(
            completedPubkeys = completedPubkeys,
            discoveredRelayUrls = discoveredRelayUrls,
        )
    }

    private suspend fun loadCache(ownPubkey: String): DiscoveryCache =
        runCatching {
            LocalSettingsStorage.getString(cacheKey(ownPubkey))
                ?.let { json.decodeFromString<DiscoveryCache>(it) }
        }.getOrNull() ?: DiscoveryCache()

    private suspend fun saveCache(ownPubkey: String, cache: DiscoveryCache) {
        LocalSettingsStorage.putString(cacheKey(ownPubkey), json.encodeToString(cache))
    }

    private fun cacheKey(ownPubkey: String): String = "$CACHE_KEY_PREFIX$ownPubkey"

    @Serializable
    private data class DiscoveryCache(
        val fetchedAtByPubkey: Map<String, Long> = emptyMap(),
    )

    private data class FetchResult(
        val completedPubkeys: Set<String>,
        val discoveredRelayUrls: List<String>,
    )

    private const val RELAY_LIST_KIND = 10002
    private const val AUTHOR_BATCH_SIZE = 100
    private const val FETCH_TIMEOUT_MS = 10_000L
    private const val FOLLOW_LOAD_TIMEOUT_MS = 15_000L
    private const val CACHE_TTL_SECONDS = 24 * 60 * 60L
    private const val CACHE_KEY_PREFIX = "followed_relay_discovery_"
}
