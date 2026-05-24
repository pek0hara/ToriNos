package com.nostr.torinos.network

import com.nostr.torinos.model.RelayMessage
import com.nostr.torinos.model.parseRelayMessage
import com.nostr.torinos.util.appLog
import com.nostr.torinos.util.logException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
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

    // ArrayDeque で保持することで、切断中のメッセージが再接続後も送信される。
    // first() でピークしてから send 後に removeFirst() するため、送信失敗時もキューに残る。
    private val pendingMessages = ArrayDeque<String>()
    private val pendingMutex = Mutex()
    private val sendSignal = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun connect(scope: CoroutineScope) {
        job = scope.launch {
            try {
                var retryDelay = 2_000L
                val maxDelay = 30_000L
                while (isActive) {
                    appLog("[Relay] connecting to $url")
                    runCatching {
                        client.webSocket(urlString = url) {
                            appLog("[Relay] connected: $url")
                            retryDelay = 2_000L
                            _connected.emit(Unit)
                            val sender = launch {
                                try {
                                    while (isActive) {
                                        while (isActive) {
                                            val msg = pendingMutex.withLock {
                                                if (pendingMessages.isEmpty()) null else pendingMessages.removeFirst()
                                            } ?: break
                                            appLog("[Relay] send to $url: $msg")
                                            try {
                                                outgoing.send(Frame.Text(msg))
                                            } catch (e: Throwable) {
                                                pendingMutex.withLock {
                                                    pendingMessages.addFirst(msg)
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
                                    appLog("[Relay] recv from $url: ${text.take(200)}")
                                    _messages.emit(parseRelayMessage(text))
                                }
                            }
                            sender.cancel()
                        }
                    }.onFailure { e ->
                        logException("Relay", e, "WebSocket loop failed for $url")
                    }
                    appLog("[Relay] disconnected from $url, retrying in ${retryDelay / 1000}s")
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
        pendingMutex.withLock {
            pendingMessages.addLast(message)
        }
        sendSignal.trySend(Unit)
    }

    fun disconnect() {
        job?.cancel()
        job = null
    }
}
