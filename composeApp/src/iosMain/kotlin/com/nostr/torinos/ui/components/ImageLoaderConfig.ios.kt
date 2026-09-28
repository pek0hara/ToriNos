package com.nostr.torinos.ui.components

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
