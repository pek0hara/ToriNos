package com.nostr.torinos.network

import com.nostr.torinos.createHttpClient
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.RelayMessage
import com.nostr.torinos.model.buildCloseMessage
import com.nostr.torinos.model.buildEventMessage
import com.nostr.torinos.model.buildReqMessage
import com.nostr.torinos.util.appLog
import com.nostr.torinos.util.loggingExceptionHandler
import com.nostr.torinos.util.networkTraceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

sealed interface RelayTarget {
    data object AllEnabled : RelayTarget
    data class Single(val url: String) : RelayTarget
}

data class RelayPublishResult(
    val succeededRelays: Set<String>,
    val failedRelays: Map<String, String>,
) {
    val successCount: Int get() = succeededRelays.size
    val failureCount: Int get() = failedRelays.size
    val totalCount: Int get() = successCount + failureCount
}

private fun RelayTarget.urls(enabledRelayUrls: List<String>): List<String> = when (this) {
    RelayTarget.AllEnabled -> enabledRelayUrls
    is RelayTarget.Single -> listOf(url).filter { it in enabledRelayUrls }
}

private fun RelayTarget.includes(relayUrl: String): Boolean = when (this) {
    RelayTarget.AllEnabled -> true
    is RelayTarget.Single -> url == relayUrl
}

object NostrRepository {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            loggingExceptionHandler("NostrRepository", "Uncaught coroutine exception"),
    )

    /** 全リレーからのメッセージを集約するバス */
    private val bus = MutableSharedFlow<RelayEnvelope>(extraBufferCapacity = 512)

    internal val httpClient = createHttpClient()
    private val stateMutex = Mutex()
    private val activeRelays = mutableMapOf<String, ActiveRelayHandle>()
    private var enabledRelayUrls: List<String> = RelayStore.defaults.filter { it.enabled }.map { it.url }
    private val activeSubscriptions = mutableMapOf<String, Pair<NostrFilter, RelayTarget>>()
    private val temporaryRelays = mutableMapOf<String, TemporaryRelayHandle>()
    private val temporarySubscriptions = mutableMapOf<String, Pair<NostrFilter, String>>()
    private val activeRelayCount = MutableStateFlow(0)

    init {
        scope.launch {
            try {
                RelayStore.relays.collect { urls ->
                    val (removedHandles, newHandles) = stateMutex.withLock {
                        enabledRelayUrls = urls
                        reconcileActiveRelaysLocked()
                    }
                    removedHandles.forEach { it.close() }
                    newHandles.forEach { it.relay.connect(scope) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                appLog("[NostrRepository] init collect error: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun createActiveRelayHandle(relay: NostrRelay): ActiveRelayHandle {
        // connect() より先に購読を張り、接続直後の EVENT/EOSE を取り逃がさない。
        val messageJob = scope.launch {
            try {
                relay.messages.collect { message ->
                    when (message) {
                        is RelayMessage.Closed -> networkTraceLog {
                            "[Repo] CLOSED from ${relay.url} subId=${message.subscriptionId} reason=${message.message}"
                        }
                        else -> networkTraceLog { "[Repo] message from ${relay.url}: $message" }
                    }
                    bus.emit(RelayEnvelope(relay.url, message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                appLog("[NostrRepository] messages collector error for ${relay.url}: ${e::class.simpleName}: ${e.message}")
            }
        }
        // connect() より先に購読を張り、接続完了通知の取り逃がしも防ぐ。
        val connectedJob = scope.launch {
            try {
                relay.connected.collect {
                    val messages = stateMutex.withLock {
                        activeSubscriptions.mapNotNull { (subId, filterAndRelay) ->
                            val (filter, target) = filterAndRelay
                            if (target.includes(relay.url)) {
                                buildReqMessage(subId, filter)
                            } else {
                                null
                            }
                        }
                    }
                    networkTraceLog { "[Repo] relay connected: ${relay.url} resending ${messages.size} subscriptions" }
                    messages.forEach { relay.send(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                appLog("[NostrRepository] connected collector error for ${relay.url}: ${e::class.simpleName}: ${e.message}")
            }
        }
        return ActiveRelayHandle(relay, listOf(messageJob, connectedJob))
    }

    private fun desiredActiveRelayUrlsLocked(): Set<String> =
        activeSubscriptions.values
            .flatMap { (_, target) -> target.urls(enabledRelayUrls) }
            .toSet()

    private fun reconcileActiveRelaysLocked(): Pair<List<ActiveRelayHandle>, List<ActiveRelayHandle>> {
        val desiredUrls = desiredActiveRelayUrlsLocked()
        val removed = (activeRelays.keys - desiredUrls)
            .mapNotNull { url -> activeRelays.remove(url) }
        val added = (desiredUrls - activeRelays.keys).map { url ->
            networkTraceLog { "[Repo] connecting to relay: $url" }
            val relay = NostrRelay(url, httpClient)
            createActiveRelayHandle(relay).also { activeRelays[url] = it }
        }
        activeRelayCount.value = activeRelays.size
        return removed to added
    }

    private fun createTemporaryRelayHandle(relay: NostrRelay): TemporaryRelayHandle {
        val messageJob = scope.launch {
            try {
                relay.messages.collect { message ->
                    when (message) {
                        is RelayMessage.Closed -> networkTraceLog {
                            "[Repo] CLOSED from ${relay.url} subId=${message.subscriptionId} reason=${message.message}"
                        }
                        else -> networkTraceLog { "[Repo] message from ${relay.url}: $message" }
                    }
                    bus.emit(RelayEnvelope(relay.url, message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                appLog("[NostrRepository] temporary messages collector error for ${relay.url}: ${e::class.simpleName}: ${e.message}")
            }
        }
        val connectedJob = scope.launch {
            try {
                relay.connected.collect {
                    val messages = stateMutex.withLock {
                        temporarySubscriptions.mapNotNull { (subId, filterAndRelay) ->
                            val (storedFilter, storedRelayUrl) = filterAndRelay
                            if (storedRelayUrl == relay.url) {
                                buildReqMessage(subId, storedFilter)
                            } else {
                                null
                            }
                        }
                    }
                    networkTraceLog { "[Repo] temporary relay connected: ${relay.url} resending ${messages.size} subscriptions" }
                    messages.forEach { relay.send(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                appLog("[NostrRepository] temporary connected collector error for ${relay.url}: ${e::class.simpleName}: ${e.message}")
            }
        }
        return TemporaryRelayHandle(relay, listOf(messageJob, connectedJob))
    }

    suspend fun subscribe(subscriptionId: String, filter: NostrFilter, relayUrl: String? = null): Set<String> =
        subscribe(
            subscriptionId = subscriptionId,
            filter = filter,
            target = relayUrl?.let(RelayTarget::Single) ?: RelayTarget.AllEnabled,
        )

    suspend fun subscribe(subscriptionId: String, filter: NostrFilter, target: RelayTarget): Set<String> {
        networkTraceLog { "[Repo] subscribe() subId='$subscriptionId' target=$target filter=$filter" }
        val message = buildReqMessage(subscriptionId, filter)
        val (targetUrls, existingTargets, newHandles) = stateMutex.withLock {
            val urls = target.urls(enabledRelayUrls).toSet()
            val targetsBeforeReconcile = activeRelays
                .filterKeys { it in urls }
                .values
                .map { it.relay }
            activeSubscriptions[subscriptionId] = Pair(filter, target)
            val (_, added) = reconcileActiveRelaysLocked()
            Triple(urls, targetsBeforeReconcile, added)
        }
        newHandles.forEach { it.relay.connect(scope) }
        existingTargets.distinctBy { it.url }.forEach { relay ->
            networkTraceLog { "[Repo] sending REQ to ${relay.url}" }
            relay.send(message)
        }
        return targetUrls
    }

    suspend fun subscribeTemporaryRelay(subscriptionId: String, filter: NostrFilter, relayUrl: String) {
        networkTraceLog { "[Repo] subscribeTemporaryRelay() subId='$subscriptionId' relay=$relayUrl filter=$filter" }
        val existingHandle = stateMutex.withLock {
            temporarySubscriptions[subscriptionId] = Pair(filter, relayUrl)
            temporaryRelays[relayUrl]
        }
        var createdRelay = false
        val handle = existingHandle ?: run {
            networkTraceLog { "[Repo] connecting to temporary relay: $relayUrl" }
            val newRelay = NostrRelay(relayUrl, httpClient)
            val newHandle = createTemporaryRelayHandle(newRelay)
            val storedHandle = stateMutex.withLock {
                temporaryRelays[relayUrl] ?: newHandle.also {
                    temporaryRelays[relayUrl] = it
                }
            }
            if (storedHandle === newHandle) {
                createdRelay = true
                newRelay.connect(scope)
            } else {
                newHandle.close()
            }
            storedHandle
        }
        if (!createdRelay) {
            handle.relay.send(buildReqMessage(subscriptionId, filter))
        }
    }

    /** サブスクリプションを解除し、リレーに CLOSE を送る */
    fun close(subscriptionId: String) {
        networkTraceLog { "[Repo] close() subId='$subscriptionId' relayCount=${relayCount}" }
        val msg = buildCloseMessage(subscriptionId)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            closeActiveSubscription(subscriptionId, msg)
        }
    }

    fun closeTemporaryRelay(subscriptionId: String) {
        networkTraceLog { "[Repo] closeTemporaryRelay() subId='$subscriptionId'" }
        val msg = buildCloseMessage(subscriptionId)
        scope.launch {
            val (targets, handlesToClose) = stateMutex.withLock {
                val relayUrl = temporarySubscriptions.remove(subscriptionId)?.second
                if (relayUrl != null) {
                    val target = listOfNotNull(temporaryRelays[relayUrl]?.relay)
                    val closeHandle = if (temporarySubscriptions.values.none { it.second == relayUrl }) {
                        temporaryRelays.remove(relayUrl)
                    } else {
                        null
                    }
                    target to listOfNotNull(closeHandle)
                } else {
                    val target = temporaryRelays.values.map { it.relay }
                    val closeHandles = temporaryRelays.values.toList()
                    temporaryRelays.clear()
                    target to closeHandles
                }
            }
            targets.forEach { it.send(msg) }
            handlesToClose.forEach { it.close() }
        }
    }

    /** サブスクリプション解除を呼び出し元のコルーチン内で送信する。 */
    suspend fun closeSuspending(subscriptionId: String) {
        networkTraceLog { "[Repo] closeSuspending() subId='$subscriptionId' relayCount=${relayCount}" }
        val msg = buildCloseMessage(subscriptionId)
        closeActiveSubscription(subscriptionId, msg)
    }

    private suspend fun closeActiveSubscription(subscriptionId: String, closeMessage: String) {
        val (targets, removedHandles) = stateMutex.withLock {
            val targetRelays = activeRelays.values.map { it.relay }
            activeSubscriptions.remove(subscriptionId)
            val (removed, _) = reconcileActiveRelaysLocked()
            targetRelays to removed
        }
        targets.forEach { it.send(closeMessage) }
        if (removedHandles.isNotEmpty()) {
            delay(100L)
        }
        removedHandles.forEach { it.close() }
    }

    /** 現在接続中のリレー数 */
    val relayCount: Int get() = activeRelayCount.value

    suspend fun targetRelayUrls(target: RelayTarget): Set<String> =
        stateMutex.withLock { target.urls(enabledRelayUrls).toSet() }

    /** 署名済みイベントを有効な全リレーに送信する。 */
    suspend fun publish(event: NostrEvent): RelayPublishResult {
        val targets = targetRelayUrls(RelayTarget.AllEnabled)
        check(targets.isNotEmpty()) { "有効なリレーがありません" }
        val result = publishToRelaysWithResult(event, targets)
        check(result.succeededRelays.isNotEmpty()) {
            "すべてのリレーへの送信に失敗しました: ${result.failedRelays.keys.joinToString()}"
        }
        return result
    }

    /** 署名済みイベントを指定リレーにだけ送信する。未接続リレーは一時接続して送る。 */
    suspend fun publishToRelays(event: NostrEvent, relayUrls: Collection<String>) {
        val result = publishToRelaysWithResult(event, relayUrls)
        check(result.failedRelays.isEmpty()) {
            "接続できないリレーがあります: ${result.failedRelays.keys.joinToString()}"
        }
    }

    internal suspend fun publishToRelaysWithResult(
        event: NostrEvent,
        relayUrls: Collection<String>,
    ): RelayPublishResult = coroutineScope {
        val targets = relayUrls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) {
            return@coroutineScope RelayPublishResult(emptySet(), emptyMap())
        }

        val message = buildEventMessage(event)
        networkTraceLog { "[Repo] publish event id=${event.id.take(8)} to ${targets.size} relays" }

        val results = targets.map { url ->
            async {
                url to runCatching {
                    val activeRelay = stateMutex.withLock { activeRelays[url]?.relay }
                    if (activeRelay != null) {
                        check(withTimeoutOrNull(PUBLISH_TIMEOUT_MS) {
                            activeRelay.sendAndAwait(message)
                            true
                        } == true) { "送信がタイムアウトしました" }
                    } else {
                        val relay = NostrRelay(url, httpClient)
                        try {
                            relay.connect(this)
                            check(withTimeoutOrNull(PUBLISH_TIMEOUT_MS) {
                                relay.connected.first()
                                relay.sendAndAwait(message)
                                true
                            } == true) { "接続または送信がタイムアウトしました" }
                        } finally {
                            relay.disconnect()
                        }
                    }
                }.onFailure { error ->
                    appLog("[Repo] publish failed for $url: ${error::class.simpleName}: ${error.message}")
                }
            }
        }.awaitAll()

        RelayPublishResult(
            succeededRelays = results.filter { it.second.isSuccess }.mapTo(linkedSetOf()) { it.first },
            failedRelays = results.mapNotNull { (url, result) ->
                result.exceptionOrNull()?.let { error ->
                    url to (error.message ?: "送信に失敗しました")
                }
            }.toMap(),
        )
    }

    /**
     * 最初の1リレーへの送信成功で戻る。残りのリレーへの送信はリポジトリの
     * スコープで継続するため、フォローなどの楽観的UIを遅いリレーがブロックしない。
     */
    internal suspend fun publishToRelaysUntilFirstSuccess(
        event: NostrEvent,
        relayUrls: Collection<String>,
        onRelayResult: suspend (RelayPublishResult) -> Unit = {},
    ): RelayPublishResult {
        val targets = relayUrls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return RelayPublishResult(emptySet(), emptyMap())

        val completion = CompletableDeferred<RelayPublishResult>()
        val resultMutex = Mutex()
        val failures = linkedMapOf<String, String>()
        var completedCount = 0

        targets.forEach { relayUrl ->
            scope.launch {
                val result = publishToRelaysWithResult(event, listOf(relayUrl))
                runCatching { onRelayResult(result) }
                    .onFailure { error ->
                        appLog("[Repo] publish result callback failed: ${error::class.simpleName}: ${error.message}")
                    }
                val completedResult = resultMutex.withLock {
                    completedCount++
                    failures.putAll(result.failedRelays)
                    when {
                        result.succeededRelays.isNotEmpty() -> RelayPublishResult(
                            succeededRelays = result.succeededRelays,
                            failedRelays = failures.toMap(),
                        )
                        completedCount == targets.size -> RelayPublishResult(
                            succeededRelays = emptySet(),
                            failedRelays = failures.toMap(),
                        )
                        else -> null
                    }
                }
                if (completedResult != null) completion.complete(completedResult)
            }
        }

        return completion.await()
    }

    fun events(subscriptionId: String): Flow<NostrEvent> =
        bus
            .filter { it.message is RelayMessage.Event && it.message.subscriptionId == subscriptionId }
            .map { (it.message as RelayMessage.Event).event }

    /** 指定サブスクリプションの EOSE または CLOSED（リレー側でサブスクが終了）を通知する Flow */
    fun eose(subscriptionId: String): Flow<Unit> =
        bus
            .filter { msg ->
                (msg.message is RelayMessage.EndOfStoredEvents && msg.message.subscriptionId == subscriptionId) ||
                (msg.message is RelayMessage.Closed && msg.message.subscriptionId == subscriptionId)
            }
            .map { }

    fun eoseRelays(subscriptionId: String): Flow<String> =
        bus
            .filter { envelope ->
                (envelope.message is RelayMessage.EndOfStoredEvents &&
                    envelope.message.subscriptionId == subscriptionId) ||
                    (envelope.message is RelayMessage.Closed &&
                        envelope.message.subscriptionId == subscriptionId)
            }
            .map { it.relayUrl }

    fun endOfStoredEvents(subscriptionId: String): Flow<Unit> =
        bus
            .filter { it.message is RelayMessage.EndOfStoredEvents && it.message.subscriptionId == subscriptionId }
            .map { }

    /** リレーが CLOSED を送ってきたときだけ通知する Flow */
    fun closed(subscriptionId: String): Flow<Unit> =
        bus
            .filter { it.message is RelayMessage.Closed && it.message.subscriptionId == subscriptionId }
            .map { }

    fun closedMessages(subscriptionId: String): Flow<RelayMessage.Closed> =
        bus
            .filter { it.message is RelayMessage.Closed && it.message.subscriptionId == subscriptionId }
            .map { it.message as RelayMessage.Closed }

    private const val PUBLISH_TIMEOUT_MS = 10_000L
}

private data class RelayEnvelope(
    val relayUrl: String,
    val message: RelayMessage,
)

private data class TemporaryRelayHandle(
    val relay: NostrRelay,
    val collectorJobs: List<kotlinx.coroutines.Job>,
) {
    fun close() {
        relay.disconnect()
        collectorJobs.forEach { it.cancel() }
    }
}

private data class ActiveRelayHandle(
    val relay: NostrRelay,
    val collectorJobs: List<kotlinx.coroutines.Job>,
) {
    fun close() {
        relay.disconnect()
        collectorJobs.forEach { it.cancel() }
    }
}
