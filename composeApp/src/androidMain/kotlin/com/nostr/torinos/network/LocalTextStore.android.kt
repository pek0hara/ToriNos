package com.nostr.torinos.network

import com.nostr.torinos.ToriNosApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.Path
import okio.Path.Companion.toOkioPath

internal actual fun appCacheDirectory(): Path = ToriNosApp.appContext.cacheDir.toOkioPath()

internal actual val cacheFileDispatcher: CoroutineDispatcher = Dispatchers.IO
