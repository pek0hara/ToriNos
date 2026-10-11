package com.nostr.torinos.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// IO のスレッドで止まっても Default の枠を使わないので、ノンスを作る側が動ける。
internal actual val relayConnectionDispatcher: CoroutineDispatcher = Dispatchers.IO
