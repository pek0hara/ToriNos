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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object NostrRepository {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            loggingExceptionHandler("NostrRepository", "Uncaught coroutine exception"),
    )

    /** 全リレーからのメッセージを集約するバス */
    private val bus = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 512)

    internal val httpClient = createHttpClient()
    private val activeRelays = mutableMapOf<String, NostrRelay>()
    /** subId → (filter, targetRelayUrl) targetRelayUrl=null は全リレー対象 */
    private val activeSubscriptions = mutableMapOf<String, Pair<NostrFilter, String?>>()

    init {
        scope.launch {
            try {
            RelayStore.relays.collect { urls ->
                // 削除されたリレーを切断
                (activeRelays.keys - urls.toSet()).forEach { url ->
                    activeRelays.remove(url)?.disconnect()
                }
                // 追加されたリレーに接続
                (urls - activeRelays.keys.toSet()).forEach { url ->
                    appLog("[Repo] connecting to relay: $url")
                    val relay = NostrRelay(url, httpClient)
                    activeRelays[url] = relay
                    // connect() より先に購読を張り、接続直後の EVENT/EOSE を取り逃がさない。
                    scope.launch {
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
                    scope.launch {
                        try {
                            relay.connected.collect {
                                appLog("[Repo] relay connected: ${relay.url} resending ${activeSubscriptions.size} subscriptions")
                                activeSubscriptions.forEach { (subId, filterAndRelay) ->
                                    val (filter, targetUrl) = filterAndRelay
                                    if (targetUrl == null || targetUrl == relay.url) {
                                        relay.send(buildReqMessage(subId, filter))
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            appLog("[NostrRepository] connected collector error for ${relay.url}: ${e::class.simpleName}: ${e.message}")
                        }
                    }
                    relay.connect(scope)
                }
            }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                appLog("[NostrRepository] init collect error: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    suspend fun subscribe(subscriptionId: String, filter: NostrFilter, relayUrl: String? = null) {
        appLog("[Repo] subscribe() subId='$subscriptionId' relay=${relayUrl ?: "all"} filter=$filter")
        activeSubscriptions[subscriptionId] = Pair(filter, relayUrl)
        val message = buildReqMessage(subscriptionId, filter)
        val targets = if (relayUrl != null) listOfNotNull(activeRelays[relayUrl]) else activeRelays.values.toList()
        targets.forEach { relay ->
            appLog("[Repo] sending REQ to ${relay.url}")
            relay.send(message)
        }
    }

    /** サブスクリプションを解除し、リレーに CLOSE を送る */
    fun close(subscriptionId: String) {
        appLog("[Repo] close() subId='$subscriptionId' relayCount=${activeRelays.size}")
        activeSubscriptions.remove(subscriptionId)
        val msg = buildCloseMessage(subscriptionId)
        scope.launch {
            activeRelays.values.forEach { it.send(msg) }
        }
    }

    /** サブスクリプション解除を呼び出し元のコルーチン内で送信する。 */
    suspend fun closeSuspending(subscriptionId: String) {
        appLog("[Repo] closeSuspending() subId='$subscriptionId' relayCount=${activeRelays.size}")
        activeSubscriptions.remove(subscriptionId)
        val msg = buildCloseMessage(subscriptionId)
        activeRelays.values.forEach { it.send(msg) }
    }

    /** 現在接続中のリレー数 */
    val relayCount: Int get() = activeRelays.size

    /** 署名済みイベントを全リレーに送信 */
    suspend fun publish(event: NostrEvent) {
        val message = buildEventMessage(event)
        appLog("[Repo] publish event id=${event.id.take(8)}")
        activeRelays.values.forEach { it.send(message) }
    }

    /** 署名済みイベントを指定リレーにだけ送信する。未接続リレーは一時接続して送る。 */
    suspend fun publishToRelays(event: NostrEvent, relayUrls: Collection<String>) = coroutineScope {
        val targets = relayUrls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return@coroutineScope

        val message = buildEventMessage(event)
        appLog("[Repo] publish event id=${event.id.take(8)} to ${targets.size} relays")

        val failedRelays = mutableListOf<String>()
        targets.forEach { url ->
            val activeRelay = activeRelays[url]
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
