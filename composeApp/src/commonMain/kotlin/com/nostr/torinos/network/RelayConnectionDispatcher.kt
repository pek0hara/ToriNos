package com.nostr.torinos.network

import kotlinx.coroutines.CoroutineDispatcher

/**
 * リレーの WebSocket 接続を動かすディスパッチャー。
 *
 * JVM 版の Ktor は接続時に [io.ktor.util.generateNonce] を呼び、用意済みの値がないと
 * `runBlocking` でスレッドを止めて待つ。値を作るコルーチンは Dispatchers.Default で動くため、
 * 接続を Default で始めると、同時接続が CPU 数を超えたときに Default の枠が埋まり、
 * 値を作る側が動けずに全体が止まる（docs/relay-connect-dispatcher-hang.md）。
 */
internal expect val relayConnectionDispatcher: CoroutineDispatcher
