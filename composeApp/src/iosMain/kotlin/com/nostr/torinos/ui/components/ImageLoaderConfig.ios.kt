package com.nostr.torinos.ui.components

import coil3.PlatformContext
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

internal actual fun estimatedAvailableMemoryBytes(context: PlatformContext): Long =
    NSProcessInfo.processInfo.physicalMemory.toLong()

internal actual fun appImageCacheDirectory(context: PlatformContext): Path {
    val cachesDirectory = (
        NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
        ) ?: NSTemporaryDirectory()
    return "$cachesDirectory/image_cache".toPath()
}
