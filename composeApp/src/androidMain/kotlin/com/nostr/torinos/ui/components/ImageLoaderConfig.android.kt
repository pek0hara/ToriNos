package com.nostr.torinos.ui.components

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import coil3.PlatformContext
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File

internal actual fun estimatedAvailableMemoryBytes(context: PlatformContext): Long {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val isLargeHeap = (context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0
    val memoryClassMegabytes = when {
        activityManager == null -> DefaultMemoryClassMegabytes
        isLargeHeap -> activityManager.largeMemoryClass
        else -> activityManager.memoryClass
    }
    return memoryClassMegabytes * 1024L * 1024L
}

internal actual fun appImageCacheDirectory(context: PlatformContext): Path =
    File(context.cacheDir, "image_cache").toOkioPath()

private const val DefaultMemoryClassMegabytes = 256
