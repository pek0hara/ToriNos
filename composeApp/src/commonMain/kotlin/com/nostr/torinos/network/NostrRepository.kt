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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

object NostrRepository {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            loggingExceptionHandler("NostrRepository", "Uncaught coroutine exception"),
    )

    /** 全リレーからのメッセージを集約するバス */
    private val bus = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 512)

    internal val httpClient = createHttpClient()
    private val stateMutex = Mutex()
    private val activeRelays = mutableMapOf<String, ActiveRelayHandle>()
    /** subId → (filter, targetRelayUrl) targetRelayUrl=null は全リレー対象 */
    private val activeSubscriptions = mutableMapOf<String, Pair<NostrFilter, String?>>()
    private val temporaryRelays = mutableMapOf<String, TemporaryRelayHandle>()
    private val temporarySubscriptions = mutableMapOf<String, Pair<NostrFilter, String>>()
    private val activeRelayCount = MutableStateFlow(0)

    init {
        scope.launch {
            try {
                RelayStore.relays.collect { urls ->
                    val desiredUrls = urls.toSet()
                    val (removedHandles, newUrls) = stateMutex.withLock {
                        val removed = (activeRelays.keys - desiredUrls)
                            .mapNotNull { url -> activeRelays.remove(url) }
                        activeRelayCount.value = activeRelays.size
                        val added = urls.filterNot { it in activeRelays }
                        removed to added
                    }
                    removedHandles.forEach { it.close() }

                    // 追加されたリレーに接続
                    newUrls.forEach { url ->
                        appLog("[Repo] connecting to relay: $url")
                        val relay = NostrRelay(url, httpClient)
                        val handle = createActiveRelayHandle(relay)
                        val shouldConnect = stateMutex.withLock {
                            if (url in activeRelays) {
                                false
                            } else {
                                activeRelays[url] = handle
                                activeRelayCount.value = activeRelays.size
                                true
                            }
                        }
                        if (shouldConnect) {
                            relay.connect(scope)
                        } else {
                            handle.close()
                        }
                    }
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
                        is RelayMessage.Closed -> appLog("[Repo] CLOSED from ${relay.url} subId=${message.subscriptionId} reason=${message.message}")
                        else -> appLog("[Repo] message from ${relay.url}: $message")
                    }
                    bus.emit(message)
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
                            val (filter, targetUrl) = filterAndRelay
                            if (targetUrl == null || targetUrl == relay.url) {
                                buildReqMessage(subId, filter)
                            } else {
                                null
                            }
                        }
                    }
                    appLog("[Repo] relay connected: ${relay.url} resending ${messages.size} subscriptions")
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

    private fun createTemporaryRelayHandle(relay: NostrRelay): TemporaryRelayHandle {
        val messageJob = scope.launch {
            try {
                relay.messages.collect { message ->
                    when (message) {
                        is RelayMessage.Closed -> appLog("[Repo] CLOSED from ${relay.url} subId=${message.subscriptionId} reason=${message.message}")
                        else -> appLog("[Repo] message from ${relay.url}: $message")
                    }
                    bus.emit(message)
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
                    appLog("[Repo] temporary relay connected: ${relay.url} resending ${messages.size} subscriptions")
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

    suspend fun subscribe(subscriptionId: String, filter: NostrFilter, relayUrl: String? = null) {
        appLog("[Repo] subscribe() subId='$subscriptionId' relay=${relayUrl ?: "all"} filter=$filter")
        val message = buildReqMessage(subscriptionId, filter)
        val targets = stateMutex.withLock {
            activeSubscriptions[subscriptionId] = Pair(filter, relayUrl)
            if (relayUrl != null) {
                listOfNotNull(activeRelays[relayUrl]?.relay)
            } else {
                activeRelays.values.map { it.relay }
            }
        }
        targets.forEach { relay ->
            appLog("[Repo] sending REQ to ${relay.url}")
            relay.send(message)
        }
    }

    suspend fun subscribeTemporaryRelay(subscriptionId: String, filter: NostrFilter, relayUrl: String) {
        appLog("[Repo] subscribeTemporaryRelay() subId='$subscriptionId' relay=$relayUrl filter=$filter")
        val existingHandle = stateMutex.withLock {
            temporarySubscriptions[subscriptionId] = Pair(filter, relayUrl)
            temporaryRelays[relayUrl]
        }
        var createdRelay = false
        val handle = existingHandle ?: run {
            appLog("[Repo] connecting to temporary relay: $relayUrl")
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
        appLog("[Repo] close() subId='$subscriptionId' relayCount=${relayCount}")
        val msg = buildCloseMessage(subscriptionId)
        scope.launch {
            val targets = stateMutex.withLock {
                activeSubscriptions.remove(subscriptionId)
                activeRelays.values.map { it.relay }
            }
            targets.forEach { it.send(msg) }
        }
    }

    fun closeTemporaryRelay(subscriptionId: String) {
        appLog("[Repo] closeTemporaryRelay() subId='$subscriptionId'")
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
        appLog("[Repo] closeSuspending() subId='$subscriptionId' relayCount=${relayCount}")
        val msg = buildCloseMessage(subscriptionId)
        val targets = stateMutex.withLock {
            activeSubscriptions.remove(subscriptionId)
            activeRelays.values.map { it.relay }
        }
        targets.forEach { it.send(msg) }
    }

    /** 現在接続中のリレー数 */
    val relayCount: Int get() = activeRelayCount.value

    /** 署名済みイベントを全リレーに送信 */
    suspend fun publish(event: NostrEvent) {
        val message = buildEventMessage(event)
        appLog("[Repo] publish event id=${event.id.take(8)}")
        val targets = stateMutex.withLock {
            activeRelays.values.map { it.relay }
        }
        targets.forEach { it.send(message) }
    }

    /** 署名済みイベントを指定リレーにだけ送信する。未接続リレーは一時接続して送る。 */
    suspend fun publishToRelays(event: NostrEvent, relayUrls: Collection<String>) = coroutineScope {
        val targets = relayUrls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return@coroutineScope

        val message = buildEventMessage(event)
        appLog("[Repo] publish event id=${event.id.take(8)} to ${targets.size} relays")

        val failedRelays = mutableListOf<String>()
        targets.forEach { url ->
            val activeRelay = stateMutex.withLock { activeRelays[url]?.relay }
            if (activeRelay != null) {
                activeRelay.send(message)
            } else {
                val relay = NostrRelay(url, httpClient)
                relay.connect(this)
                val connected = withTimeoutOrNull(10_000L) {
                    relay.connected.first()
                } != null
                if (connected) {
                    relay.send(message)
                    delay(500L)
                } else {
                    appLog("[Repo] timeout connecting to relay for targeted publish: $url")
                    failedRelays += url
                }
                relay.disconnect()
            }
        }

        check(failedRelays.isEmpty()) {
            "接続できないリレーがあります: ${failedRelays.joinToString()}"
        }
    }

    fun events(subscriptionId: String): Flow<NostrEvent> =
        bus
            .filterIsInstance<RelayMessage.Event>()
            .filter { it.subscriptionId == subscriptionId }
            .map { it.event }

    /** 指定サブスクリプションの EOSE または CLOSED（リレー側でサブスクが終了）を通知する Flow */
    fun eose(subscriptionId: String): Flow<Unit> =
        bus
            .filter { msg ->
                (msg is RelayMessage.EndOfStoredEvents && msg.subscriptionId == subscriptionId) ||
                (msg is RelayMessage.Closed && msg.subscriptionId == subscriptionId)
            }
            .map { }

    /** リレーが CLOSED を送ってきたときだけ通知する Flow */
    fun closed(subscriptionId: String): Flow<Unit> =
        bus
            .filterIsInstance<RelayMessage.Closed>()
            .filter { it.subscriptionId == subscriptionId }
            .map { }
}

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
