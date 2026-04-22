package com.example.nostr.network

import com.example.nostr.createHttpClient
import com.example.nostr.model.NostrEvent
import com.example.nostr.model.NostrFilter
import com.example.nostr.model.RelayMessage
import com.example.nostr.model.buildCloseMessage
import com.example.nostr.model.buildReqMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object NostrRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 全リレーからのメッセージを集約するバス */
    private val bus = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 512)

    private val client = createHttpClient()
    private val activeRelays = mutableMapOf<String, NostrRelay>()
    private val activeSubscriptions = mutableMapOf<String, NostrFilter>()

    init {
        scope.launch {
            RelayStore.relays.collect { urls ->
                // 削除されたリレーを切断
                (activeRelays.keys - urls.toSet()).forEach { url ->
                    activeRelays.remove(url)?.disconnect()
                }
                // 追加されたリレーに接続
                (urls - activeRelays.keys.toSet()).forEach { url ->
                    println("[Repo] connecting to relay: $url")
                    val relay = NostrRelay(url, client)
                    activeRelays[url] = relay
                    // connect() より先に購読を張り、接続直後の EVENT/EOSE を取り逃がさない。
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        relay.messages.collect { message ->
                            when (message) {
                                is RelayMessage.Closed -> println("[Repo] CLOSED from ${relay.url} subId=${message.subscriptionId} reason=${message.message}")
                                else -> println("[Repo] message from ${relay.url}: $message")
                            }
                            bus.emit(message)
                        }
                    }
                    // connect() より先に購読を張り、接続完了通知の取り逃がしも防ぐ。
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        relay.connected.collect {
                            println("[Repo] relay connected: ${relay.url} resending ${activeSubscriptions.size} subscriptions")
                            activeSubscriptions.forEach { (subId, filter) ->
                                relay.send(buildReqMessage(subId, filter))
                            }
                        }
                    }
                    relay.connect(scope)
                }
            }
        }
    }

    suspend fun subscribe(subscriptionId: String, filter: NostrFilter) {
        println("[Repo] subscribe() subId='$subscriptionId' relayCount=${activeRelays.size} filter=$filter")
        activeSubscriptions[subscriptionId] = filter
        val message = buildReqMessage(subscriptionId, filter)
        activeRelays.values.forEach { relay ->
            println("[Repo] sending REQ to ${relay.url}")
            relay.send(message)
        }
    }

    /** サブスクリプションを解除し、リレーに CLOSE を送る */
    fun close(subscriptionId: String) {
        println("[Repo] close() subId='$subscriptionId' relayCount=${activeRelays.size}")
        activeSubscriptions.remove(subscriptionId)
        val msg = buildCloseMessage(subscriptionId)
        scope.launch {
            activeRelays.values.forEach { it.send(msg) }
        }
    }

    /** 現在接続中のリレー数 */
    val relayCount: Int get() = activeRelays.size

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
}
