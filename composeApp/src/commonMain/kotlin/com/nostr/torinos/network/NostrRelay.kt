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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NostrRelay(
    val url: String,
    private val client: HttpClient,
) {
    private val _messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 256)
    val messages: SharedFlow<RelayMessage> = _messages.asSharedFlow()

    // 接続完了を後続の購読側が取り逃がすと、REQ 再送が走らずデータ取得が始まらない。
    private val _connected = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val connected: SharedFlow<Unit> = _connected.asSharedFlow()

    // 切断中のメッセージを保持し、REQ/CLOSE は subId 単位で最新だけを残す。
    // 送信失敗時は先頭に戻すため、再接続後に再送される。
    private val pendingMessages = mutableListOf<PendingMessage>()
    private val pendingMutex = Mutex()
    private val sendSignal = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun connect(scope: CoroutineScope) {
        job = scope.launch {
            try {
                var retryDelay = 2_000L
                val maxDelay = 30_000L
                while (isActive) {
                    networkTraceLog { "[Relay] connecting to $url" }
                    runCatching {
                        client.webSocket(urlString = url) {
                            networkTraceLog { "[Relay] connected: $url" }
                            retryDelay = 2_000L
                            _connected.emit(Unit)
                            val sender = launch {
                                try {
                                    while (isActive) {
                                        while (isActive) {
                                            val pending = pendingMutex.withLock {
                                                if (pendingMessages.isEmpty()) null else pendingMessages.removeAt(0)
                                            } ?: break
                                            val msg = pending.text
                                            networkTraceLog { "[Relay] send to $url: $msg" }
                                            try {
                                                outgoing.send(Frame.Text(msg))
                                                pending.completion?.complete(Unit)
                                            } catch (e: Throwable) {
                                                pendingMutex.withLock {
                                                    pendingMessages.add(0, pending)
                                                }
                                                throw e
                                            }
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
                                    _messages.emit(parseRelayMessage(text))
                                }
                            }
                            sender.cancel()
                        }
                    }.onFailure { e ->
                        logException("Relay", e, "WebSocket loop failed for $url")
                    }
                    networkTraceLog { "[Relay] disconnected from $url, retrying in ${retryDelay / 1000}s" }
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(maxDelay)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("Relay", e, "connect loop crashed for $url")
            }
        }
    }

    suspend fun send(message: String) {
        enqueue(PendingMessage(message, subscriptionQueueKey(message)))
    }

    /** WebSocket へフレームを書き込むまで待つ。投稿結果の判定に使用する。 */
    suspend fun sendAndAwait(message: String) {
        val completion = CompletableDeferred<Unit>()
        val pending = PendingMessage(message, subscriptionQueueKey(message), completion)
        enqueue(pending)
        try {
            completion.await()
        } catch (e: CancellationException) {
            pendingMutex.withLock {
                pendingMessages.remove(pending)
            }
            throw e
        }
    }

    private suspend fun enqueue(pending: PendingMessage) {
        pendingMutex.withLock {
            pending.key?.let { key ->
                pendingMessages.removeAll { it.key == key }
            }
            pendingMessages.add(pending)
        }
        sendSignal.trySend(Unit)
    }

    fun disconnect() {
        job?.cancel()
        job = null
    }
}

private data class PendingMessage(
    val text: String,
    val key: String?,
    val completion: CompletableDeferred<Unit>? = null,
)

private val relayMessageJson = Json { ignoreUnknownKeys = true }

private fun subscriptionQueueKey(message: String): String? = runCatching {
    val array = relayMessageJson.parseToJsonElement(message).jsonArray
    val type = array.getOrNull(0)?.jsonPrimitive?.content ?: return@runCatching null
    if (type != "REQ" && type != "CLOSE") return@runCatching null
    array.getOrNull(1)?.jsonPrimitive?.content
}.getOrNull()
