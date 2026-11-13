package com.nostr.torinos.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

internal actual fun appCacheDirectory(): Path = (
    NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String
    ?: NSTemporaryDirectory()
    ).toPath()

// Dispatchers.IOはKotlin/Nativeではinternalで使えないため、Defaultのうち1スレッドに限る。
internal actual val cacheFileDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
