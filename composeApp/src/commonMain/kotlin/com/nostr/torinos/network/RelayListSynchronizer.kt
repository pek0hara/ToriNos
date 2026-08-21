package com.nostr.torinos.network

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.loadPublicKey
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.util.appLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * ログインしたアカウントが他クライアントから公開した NIP-65 リレーリストを取得し、
 * ToriNos の端末設定へ反映する。
 */
object RelayListSynchronizer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var syncGeneration = 0L
    private val operationMutex = Mutex()
    private val retryMutex = Mutex()
    private var observerJob: Job? = null
    private var outboxRetryJob: Job? = null
    private var observedPubkey: String? = null

    suspend fun syncFromRelays(pubkey: String): Boolean = operationMutex.withLock {
        RelayStore.isLoaded.first { it }
        startObserving(pubkey)
        scope.launch { retryPendingPublish(pubkey) }

        val latestEvent = fetchLatestEvent(pubkey) ?: return@withLock false
        val publishedUrls = relayUrlsFromTags(latestEvent.tags)
        if (publishedUrls.isEmpty()) return@withLock false

        RelayStore.applyPublishedRelayUrls(publishedUrls)
        appLog("[RelayListSynchronizer] applied ${publishedUrls.size} relays for ${pubkey.take(8)}")
        true
    }

    /** 読み込み済みの kind:10002 に、明示された追加・削除だけを反映する。 */
    suspend fun updatePublishedRelayList(
        additions: Set<String>,
        removals: Set<String>,
    ): RelayListUpdateResult = operationMutex.withLock {
        val privateKeyHex = KeyStorage.loadPrivateKey() ?: error("秘密鍵がありません")
        val pubkey = loadPublicKey() ?: error("公開鍵を取得できません")
        val latestEvent = RelayListEventCache.getOrLoad(pubkey)
        val legacyContactEvent = if (latestEvent == null) FollowRepository.latestFollowListEvent() else null
        val currentTags = latestEvent?.tags ?: legacyContactEvent
            ?.let(::legacyRelayTagsFromContactEvent)
            .orEmpty()
        check(currentTags.isNotEmpty()) {
            "既存の公開リレーリストを確認できないため、更新を中止しました"
        }
        val updatedTags = applyRelayListChanges(
            currentTags = currentTags,
            additions = additions,
            removals = removals,
        )
        checkRelayListCanBePublished(updatedTags)
        val updatedRelayUrls = relayUrlsFromTags(updatedTags)
        val targetRelayUrls = (
            RelayStore.enabledRelayUrlsSnapshot() +
                RelayStore.defaults.map { it.url } +
                relayUrlsFromTags(currentTags) +
                updatedRelayUrls
            )
            .mapNotNull(::normalizeRelayUrl)
            .distinct()
        check(targetRelayUrls.isNotEmpty()) { "送信先リレーがありません" }

        val event = signEvent(
            privateKeyHex = privateKeyHex,
            content = latestEvent?.content.orEmpty(),
            kind = RELAY_LIST_KIND,
            tags = updatedTags,
            createdAt = maxOf(
                Clock.System.now().epochSeconds,
                maxOf(latestEvent?.createdAt ?: -1L, legacyContactEvent?.createdAt ?: -1L) + 1L,
            ),
        )
        check(event.pubkey == pubkey) { "アカウントが切り替わったため、更新を中止しました" }
        RelayListPublishOutbox.enqueue(event, targetRelayUrls)
        val publishResult = publishRelayListEvent(event, targetRelayUrls)
        check(publishResult.succeededRelays.isNotEmpty()) { "すべてのリレーへの送信に失敗しました" }
        check(loadPublicKey() == pubkey) { "アカウントが切り替わったため、端末設定への反映を中止しました" }
        RelayListEventCache.putEventAndPersist(event)
        RelayListUpdateResult(event = event, publishResult = publishResult)
    }

    /** 新規生成したアカウントにだけ初期 kind:10002 を作る。 */
    suspend fun initializeNewAccountRelayList(): Result<Unit> = operationMutex.withLock {
        runCatching {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: error("秘密鍵がありません")
            val relayUrls = RelayStore.defaults
                .filter { it.enabled }
                .mapNotNull { normalizeRelayUrl(it.url) }
                .distinct()
            check(relayUrls.isNotEmpty()) { "初期リレーがありません" }
            val event = signEvent(
                privateKeyHex = privateKeyHex,
                content = "",
                kind = RELAY_LIST_KIND,
                tags = relayUrls.map { listOf("r", it) },
            )
            RelayListPublishOutbox.enqueue(event, relayUrls)
            RelayListEventCache.putEventAndPersist(event)
            val result = publishRelayListEvent(event, relayUrls)
            check(result.succeededRelays.isNotEmpty()) { "初期リレーリストを送信できませんでした" }
        }
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
        bypassCache: Boolean = false,
    ): NostrEvent? {
        RelayStore.isLoaded.first { it }
        val cachedEvent = RelayListEventCache.getOrLoad(pubkey)
        if (cachedEvent != null && !bypassCache) {
            onFirstResponse()
            scope.launch {
                runCatching { fetchLatestEvent(pubkey, bypassCache = true) }
                    .onFailure { e ->
                        appLog("[RelayListSynchronizer] background refresh failed: ${e.message}")
                    }
            }
            return cachedEvent
        }
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
        var latestEvent: NostrEvent? = cachedEvent

        try {
            coroutineScope {
                val completedSubscriptions = mutableSetOf<String>()
                val allComplete = CompletableDeferred<Unit>()
                val completionMutex = Mutex()
                val firstResponseMutex = Mutex()
                var firstResponseNotified = cachedEvent != null

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
        return latestEvent?.let { RelayListEventCache.putEventAndPersist(it) }
            ?: RelayListEventCache.getOrLoad(pubkey)
    }

    private suspend fun publishRelayListEvent(
        event: NostrEvent,
        relayUrls: Collection<String>,
    ): RelayPublishResult = NostrRepository.publishToRelaysUntilFirstSuccess(
        event = event,
        relayUrls = relayUrls,
        onRelayResult = { result ->
            if (result.succeededRelays.isNotEmpty()) {
                RelayListPublishOutbox.markSucceeded(event, result.succeededRelays)
            }
        },
    )

    private suspend fun retryPendingPublish(pubkey: String) {
        retryMutex.withLock {
            val pending = RelayListPublishOutbox.get(pubkey) ?: return@withLock
            if (pending.pendingRelayUrls.isEmpty()) return@withLock
            val result = publishRelayListEvent(pending.event, pending.pendingRelayUrls)
            if (result.succeededRelays.isNotEmpty()) {
                RelayListEventCache.putEventAndPersist(pending.event)
                relayUrlsFromTags(pending.event.tags)
                    .takeIf { it.isNotEmpty() }
                    ?.let { RelayStore.applyPublishedRelayUrls(it) }
            }
        }
    }

    private fun startObserving(pubkey: String) {
        if (observedPubkey == pubkey && observerJob?.isActive == true) return
        observerJob?.cancel()
        outboxRetryJob?.cancel()
        observedPubkey?.let { NostrRepository.close(observerSubscriptionId(it)) }
        observedPubkey = pubkey
        val subscriptionId = observerSubscriptionId(pubkey)
        observerJob = scope.launch {
            val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
                NostrRepository.events(subscriptionId).collect { event ->
                    if (event.kind != RELAY_LIST_KIND || event.pubkey != pubkey) return@collect
                    val cached = RelayListEventCache.getOrLoad(pubkey)
                    val pending = RelayListPublishOutbox.get(pubkey)?.event
                    val latestKnown = listOfNotNull(cached, pending).maxWithOrNull(RELAY_EVENT_COMPARATOR)
                    if (latestKnown != null && !event.isNewerThan(latestKnown)) return@collect
                    val urls = relayUrlsFromTags(event.tags)
                    if (urls.isEmpty()) {
                        appLog("[RelayListSynchronizer] ignored empty relay list from network")
                        return@collect
                    }
                    RelayListEventCache.putEventAndPersist(event)
                    RelayListPublishOutbox.discardIfOlderThan(event)
                    RelayStore.applyPublishedRelayUrls(urls)
                }
            }
            try {
                NostrRepository.subscribe(
                    subscriptionId = subscriptionId,
                    filter = NostrFilter(kinds = listOf(RELAY_LIST_KIND), authors = listOf(pubkey), limit = 1),
                )
                awaitCancellation()
            } finally {
                eventJob.cancel()
                NostrRepository.close(subscriptionId)
            }
        }
        outboxRetryJob = scope.launch {
            while (true) {
                delay(OUTBOX_RETRY_INTERVAL_MS)
                runCatching { retryPendingPublish(pubkey) }
                    .onFailure { e -> appLog("[RelayListSynchronizer] outbox retry failed: ${e.message}") }
            }
        }
    }

    fun stopObserving() {
        observerJob?.cancel()
        outboxRetryJob?.cancel()
        observedPubkey?.let { NostrRepository.close(observerSubscriptionId(it)) }
        observerJob = null
        outboxRetryJob = null
        observedPubkey = null
    }

    private fun observerSubscriptionId(pubkey: String): String =
        "relay-list-observer-${pubkey.take(16)}"

    private fun NostrEvent.isNewerThan(other: NostrEvent): Boolean =
        createdAt > other.createdAt || (createdAt == other.createdAt && id < other.id)

    private const val RELAY_LIST_KIND = 10002
    private const val SYNC_TIMEOUT_MS = 5_000L
    private const val OUTBOX_RETRY_INTERVAL_MS = 30_000L

    private val RELAY_EVENT_COMPARATOR = compareBy<NostrEvent> { it.createdAt }
        .thenByDescending { it.id }
}

data class RelayListUpdateResult(
    val event: NostrEvent,
    val publishResult: RelayPublishResult,
)

data class PublishedRelayListSnapshot(
    val urls: Set<String>,
    val hasPublishedEvent: Boolean,
)

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

internal fun checkRelayListCanBePublished(tags: List<List<String>>) {
    val relayTags = tags.filter { tag ->
        tag.size >= 2 && tag[0] == "r" && normalizeRelayUrl(tag[1]) != null
    }
    check(relayTags.isNotEmpty()) { "公開リレーを0件にはできません" }
    check(relayTags.any { it.getOrNull(2) != "read" }) {
        "最後の書き込み可能リレーは削除できません"
    }
}

internal fun legacyRelayTagsFromContactEvent(event: NostrEvent): List<List<String>> {
    if (event.kind != 3 || event.content.isBlank()) return emptyList()
    val relayObjects = runCatching { Json.parseToJsonElement(event.content).jsonObject }.getOrNull()
        ?: return emptyList()
    return relayObjects.mapNotNull { (rawUrl, configurationElement) ->
        val url = normalizeRelayUrl(rawUrl) ?: return@mapNotNull null
        val configuration = configurationElement.runCatching { jsonObject }.getOrNull()
            ?: return@mapNotNull listOf("r", url)
        val read = configuration["read"]?.jsonPrimitive?.booleanOrNull ?: true
        val write = configuration["write"]?.jsonPrimitive?.booleanOrNull ?: true
        when {
            !read && !write -> null
            read && !write -> listOf("r", url, "read")
            !read && write -> listOf("r", url, "write")
            else -> listOf("r", url)
        }
    }.distinctBy { it[1] }
}
