package com.nostr.torinos.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// このプラットフォームの Ktor は接続時にスレッドを止めて待たないので、Default のままでよい。
internal actual val relayConnectionDispatcher: CoroutineDispatcher = Dispatchers.Default
