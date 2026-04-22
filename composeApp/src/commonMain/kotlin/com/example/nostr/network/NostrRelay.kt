package com.example.nostr.network

import com.example.nostr.model.RelayMessage
import com.example.nostr.model.parseRelayMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NostrRelay(
    val url: String,
    private val client: HttpClient,
) {
    private val _messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 256)
    val messages: SharedFlow<RelayMessage> = _messages.asSharedFlow()

    // 接続完了を後続の購読側が取り逃がすと、REQ 再送が走らずデータ取得が始まらない。
    private val _connected = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val connected: SharedFlow<Unit> = _connected.asSharedFlow()

    private val sendQueue = Channel<String>(Channel.UNLIMITED)
    private var job: Job? = null

    fun connect(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                println("[Relay] connecting to $url")
                runCatching {
                    client.webSocket(urlString = url) {
                        println("[Relay] connected: $url")
                        _connected.emit(Unit)
                        val sender = launch {
                            for (msg in sendQueue) {
                                println("[Relay] send to $url: $msg")
                                outgoing.send(Frame.Text(msg))
                            }
                        }
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                println("[Relay] recv from $url: ${text.take(200)}")
                                _messages.emit(parseRelayMessage(text))
                            }
                        }
                        sender.cancel()
                    }
                }.onFailure { e ->
                    println("[Relay] error on $url: $e")
                }
                // 接続が切れたら 5 秒後に再試行
                println("[Relay] disconnected from $url, retrying in 5s")
                delay(5_000)
            }
        }
    }

    suspend fun send(message: String) {
        sendQueue.send(message)
    }

    fun disconnect() {
        job?.cancel()
        job = null
    }
}
