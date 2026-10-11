package com.nostr.torinos.network

import com.nostr.torinos.model.RelayMessage
import com.nostr.torinos.model.parseRelayMessage
import com.nostr.torinos.util.appLog
import com.nostr.torinos.util.logException
import com.nostr.torinos.util.networkTraceLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RelayConnectionState {
    Connecting,
    Connected,
    Disconnected,
}

class NostrRelay(
    val url: String,
    private val client: HttpClient,
) {
    private val _messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 256)
    val messages: SharedFlow<RelayMessage> = _messages.asSharedFlow()

    // 接続完了を後続の購読側が取り逃がすと、REQ 再送が走らずデータ取得が始まらない。
    // 値は接続ごとに一意な世代。購読側は REQ/CLOSE をこの世代付きで送る（[sendControl]）。
    private val _connected = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val connected: SharedFlow<Long> = _connected.asSharedFlow()

    private val _connectionState = MutableStateFlow(RelayConnectionState.Disconnected)
    val connectionState: StateFlow<RelayConnectionState> = _connectionState.asStateFlow()

    // 送信待ち。EVENT は切断中も保持して再接続後に送る。REQ/CLOSE は接続中の世代のものだけを持つ（[RelayOutbox]）。
    private val outbox = RelayOutbox()
    private val outboxLock = SynchronousLock()
    private val okWaiters = mutableMapOf<String, MutableList<CompletableDeferred<RelayMessage.Ok>>>()
    private val okWaitersMutex = Mutex()
    private val sendSignal = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun connect(scope: CoroutineScope) {
        if (job?.isActive == true) return
        _connectionState.value = RelayConnectionState.Connecting
        job = scope.launch(relayConnectionDispatcher) {
            try {
                var retryDelay = 2_000L
                val maxDelay = 30_000L
                while (isActive) {
                    _connectionState.value = RelayConnectionState.Connecting
                    networkTraceLog { "[Relay] connecting to $url" }
                    runCatching {
                        client.webSocket(urlString = url) {
                            networkTraceLog { "[Relay] connected: $url" }
                            retryDelay = 2_000L
                            val session = outboxLock.withLock { outbox.openSession() }
                            _connectionState.value = RelayConnectionState.Connected
                            _connected.emit(session)
                            val sender = launch {
                                try {
                                    while (isActive) {
                                        while (isActive) {
                                            val pending = outboxLock.withLock { outbox.next() } ?: break
                                            val msg = pending.text
                                            networkTraceLog { "[Relay] send to $url: $msg" }
                                            try {
                                                outgoing.send(Frame.Text(msg))
                                                pending.completion?.complete(Unit)
                                            } catch (e: Throwable) {
                                                outboxLock.withLock { outbox.requeueFront(pending) }
                                                throw e
                                            }
                                            // 送信に成功した後で計測する。再キューする try の中に置くと、計測の例外で送信済みの
                                            // メッセージが再送されてしまう。
                                            TrafficMetrics.onSend(url, pending.key, msg)
                                        }
                                        sendSignal.receive()
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    logException("Relay", e, "sender error for $url")
                                    throw e
                                }
                            }
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    networkTraceLog { "[Relay] recv from $url: ${text.take(200)}" }
                                    val message = parseRelayMessage(text)
                                    TrafficMetrics.onReceive(url, message, text.length)
                                    if (message is RelayMessage.Ok) {
                                        okWaitersMutex.withLock {
                                            okWaiters.remove(message.eventId).orEmpty()
                                        }.forEach { it.complete(message) }
                                    }
                                    _messages.emit(message)
                                }
                            }
                            sender.cancel()
                        }
                    }.onFailure { e ->
                        if (e !is CancellationException) logException("Relay", e, "WebSocket loop failed for $url")
                    }
                    // 接続が切れたら、この接続で送るはずだった REQ/CLOSE は捨てる。購読側が次の接続で送り直す。
                    outboxLock.withLock { outbox.closeSession() }
                    _connectionState.value = RelayConnectionState.Disconnected
                    if (!isActive) break
                    networkTraceLog { "[Relay] disconnected from $url, retrying in ${retryDelay / 1000}s" }
                    FetchTrace.disconnected(url, retryDelay / 1000)
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(maxDelay)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("Relay", e, "connect loop crashed for $url")
            } finally {
                outboxLock.withLock { outbox.closeSession() }
                _connectionState.value = RelayConnectionState.Disconnected
            }
        }
    }

    /** REQ/CLOSE 以外（EVENT 等）を送る。切断中は保持し、次の接続で送る。 */
    suspend fun send(message: String) {
        enqueue(buildPendingMessage(message))
    }

    /**
     * REQ/CLOSE を [generation]（[connected] で受け取った世代）の接続で送る。
     * 切断中や、すでに別の接続になっているときは送らずに false を返す。購読側は次の接続時に送り直す。
     */
    fun sendControl(message: String, generation: Long): Boolean {
        val accepted = outboxLock.withLock { outbox.offerControl(message, generation) }
        if (accepted) sendSignal.trySend(Unit)
        return accepted
    }

    /** 接続中ならその世代。切断中は null。 */
    fun connectedGeneration(): Long? = outboxLock.withLock { outbox.connectedSession() }

    /** WebSocket へフレームを書き込むまで待つ。投稿結果の判定に使用する。 */
    suspend fun sendAndAwait(message: String) {
        val completion = CompletableDeferred<Unit>()
        val pending = buildPendingMessage(message, completion)
        enqueue(pending)
        try {
            completion.await()
        } catch (e: CancellationException) {
            outboxLock.withLock { outbox.remove(pending) }
            throw e
        }
    }

    /** EVENT を送信し、リレーが同じイベントIDに対する NIP-20 の OK を返すまで待つ。 */
    suspend fun sendEventAndAwaitOk(message: String, eventId: String): RelayMessage.Ok {
        // SharedFlow の子コルーチンを作らず、タイムアウト時に即座に解除できる待機表を使う。
        val response = CompletableDeferred<RelayMessage.Ok>()
        okWaitersMutex.withLock {
            okWaiters.getOrPut(eventId) { mutableListOf() }.add(response)
        }
        try {
            sendAndAwait(message)
            return response.await()
        } finally {
            okWaitersMutex.withLock {
                okWaiters[eventId]?.let { waiters ->
                    waiters.remove(response)
                    if (waiters.isEmpty()) okWaiters.remove(eventId)
                }
            }
        }
    }

    private fun enqueue(pending: PendingMessage) {
        val accepted = outboxLock.withLock {
            if (pending.key != null) {
                // REQ/CLOSE は世代を指定する sendControl を使う。誤って来た場合も接続中の世代でだけ送る。
                outbox.connectedSession()?.let { outbox.offerControl(pending.text, it) } ?: false
            } else {
                outbox.offer(pending)
                true
            }
        }
        if (accepted) sendSignal.trySend(Unit)
    }

    fun disconnect() {
        job?.cancel()
        job = null
        _connectionState.value = RelayConnectionState.Disconnected
    }
}

internal data class PendingMessage(
    val text: String,
    val key: String?,
    val isPriority: Boolean = false,
    val completion: CompletableDeferred<Unit>? = null,
    /** REQ/CLOSE を受け付けた接続の世代。EVENT 等は null で、接続をまたいで保持する。 */
    val session: Long? = null,
)

private data class ControlMessage(
    val type: String,
    val subscriptionId: String?,
)

private val relayMessageJson = Json { ignoreUnknownKeys = true }

private fun parseControlMessage(message: String): ControlMessage? = runCatching {
    val array = relayMessageJson.parseToJsonElement(message).jsonArray
    val type = array.getOrNull(0)?.jsonPrimitive?.content ?: return@runCatching null
    if (type != "REQ" && type != "CLOSE") return@runCatching null
    ControlMessage(type, array.getOrNull(1)?.jsonPrimitive?.content)
}.getOrNull()

internal fun buildPendingMessage(
    message: String,
    completion: CompletableDeferred<Unit>? = null,
): PendingMessage {
    val control = parseControlMessage(message)
    return PendingMessage(
        text = message,
        key = control?.subscriptionId,
        isPriority = control?.type == "CLOSE",
        completion = completion,
    )
}

internal fun MutableList<PendingMessage>.enqueue(pending: PendingMessage) {
    pending.key?.let { key -> removeAll { it.key == key } }
    if (pending.isPriority) add(0, pending) else add(pending)
}

/**
 * 1つのリレー接続の送信待ち。REQ/CLOSE は「どの接続で送るつもりか」を世代で持ち、
 * その接続の間だけ送る。購読状態は購読側（状態機械）だけが持ち、ここでは保持・再送しない。
 *
 * 世代はアプリ全体で一意にし、リレーを作り直しても前の世代と重ならないようにする。
 */
internal class RelayOutbox {
    private val pending = mutableListOf<PendingMessage>()
    private var session: Long? = null

    fun openSession(): Long {
        val next = nextSessionId()
        session = next
        pending.removeAll { it.session != null }
        return next
    }

    fun closeSession() {
        session = null
        pending.removeAll { it.session != null }
    }

    fun connectedSession(): Long? = session

    fun offerControl(message: String, generation: Long): Boolean {
        if (session == null || generation != session) return false
        pending.enqueue(buildPendingMessage(message).copy(session = generation))
        return true
    }

    fun offer(message: PendingMessage) = pending.enqueue(message)

    fun next(): PendingMessage? {
        while (pending.isNotEmpty()) {
            val head = pending.removeAt(0)
            if (head.isCurrent()) return head
        }
        return null
    }

    fun requeueFront(message: PendingMessage) {
        if (message.isCurrent()) pending.add(0, message)
    }

    fun remove(message: PendingMessage) {
        pending.remove(message)
    }

    private fun PendingMessage.isCurrent(): Boolean = this.session == null || this.session == session

    private companion object {
        private val sessionLock = SynchronousLock()
        private var lastSessionId = 0L

        fun nextSessionId(): Long = sessionLock.withLock { ++lastSessionId }
    }
}
