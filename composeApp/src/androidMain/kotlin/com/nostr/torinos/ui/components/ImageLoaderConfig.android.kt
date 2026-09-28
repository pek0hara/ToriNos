package com.nostr.torinos.ui.components

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import coil3.EventListener
import coil3.PlatformContext
import coil3.decode.Decoder
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
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

internal actual class MetricsEventListener : EventListener() {
    private var fetchInvoked = false
    private var decodeInvoked = false

    override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
        fetchInvoked = true
    }

    override fun fetchEnd(
        request: ImageRequest,
        fetcher: Fetcher,
        options: Options,
        result: FetchResult?,
    ) {
        val dataSource =
            (result as? SourceFetchResult)?.dataSource ?: (result as? ImageFetchResult)?.dataSource
        if (dataSource != null) ImageLoaderMetrics.recordFetch(dataSource)
    }

    override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
        decodeInvoked = true
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        if (!fetchInvoked) ImageLoaderMetrics.recordMemoryHit()
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        ImageLoaderMetrics.recordError(decodeAttempted = decodeInvoked)
    }
}

private const val DefaultMemoryClassMegabytes = 256
