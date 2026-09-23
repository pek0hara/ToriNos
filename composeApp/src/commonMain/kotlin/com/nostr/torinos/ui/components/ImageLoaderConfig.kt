package com.nostr.torinos.ui.components

import coil3.EventListener
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.decode.Decoder
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.cacheTraceLog
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.Dispatchers
import kotlin.time.TimeSource
import okio.Path

/**
 * アプリ共通の[ImageLoader]を構成する。
 *
 * Coil3の既定シングルトンは、メモリ上限が端末依存で決まる(かつ計測できない)上、ディスク
 * キャッシュも共用の一時ディレクトリ(`FileSystem.SYSTEM_TEMPORARY_DIRECTORY`)に置かれ、
 * アプリ専用のキャッシュ領域として扱われない。ここではアプリ専用のディレクトリと上限を
 * 明示し、ネットワーク・デコードの同時実行数も固定する。
 *
 * `SingletonImageLoader.setSafe`は最初のCoil API呼び出し(`AsyncImage`など)より前に
 * 呼ぶ必要があるため、各プラットフォームのエントリポイントの最初期(Androidは
 * `Application.onCreate`、iOSは`MainViewController`のcompose content生成前)で呼ぶ。
 */
internal fun registerAppImageLoader() {
    SingletonImageLoader.setSafe { context -> createAppImageLoader(context) }
}

private fun createAppImageLoader(context: PlatformContext): ImageLoader {
    val memoryCacheMaxBytes = minOf(
        (estimatedAvailableMemoryBytes(context) * MemoryCachePercentOfAvailable).toLong(),
        MemoryCacheMaximumBytes,
    )
    return ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(memoryCacheMaxBytes)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(appImageCacheDirectory(context))
                .maxSizeBytes(DiskCacheMaximumBytes)
                .build()
        }
        // Dispatchers.IOはKotlin/Nativeではinternalで使えないため、Dispatchers.Defaultを
        // limitedParallelismで用途ごとに分けて使う。
        .fetcherCoroutineContext(Dispatchers.Default.limitedParallelism(NetworkFetchConcurrency))
        .decoderCoroutineContext(Dispatchers.Default.limitedParallelism(DecodeConcurrency))
        .eventListenerFactory { MetricsEventListener() }
        .build()
        .also { ImageLoaderMetrics.attach(it) }
}

/**
 * memory/disk hit数、network fetch数、decode失敗数を集計し、[cacheTraceLog]と同じ抑制方針で
 * 一定間隔ごとの集計値としてログ出力する(イベント単位では出力しない)。
 *
 * 「プリフェッチ後に表示されなかった件数」は、プリフェッチ要求と表示要求を紐付ける仕組みが
 * 別途必要なため今回のスコープ外とし、次の課題として残す。
 */
private object ImageLoaderMetrics {
    private val lock = SynchronousLock()
    private var imageLoader: ImageLoader? = null
    private var memoryHits = 0L
    private var diskHits = 0L
    private var networkFetches = 0L
    private var otherFetches = 0L
    private var requestErrors = 0L
    private var decodeFailures = 0L
    private var lastLoggedAt = TimeSource.Monotonic.markNow()

    fun attach(imageLoader: ImageLoader) {
        lock.withLock { this.imageLoader = imageLoader }
    }

    fun recordMemoryHit() = record { memoryHits++ }

    fun recordFetch(dataSource: DataSource) = record {
        when (dataSource) {
            DataSource.DISK -> diskHits++
            DataSource.NETWORK -> networkFetches++
            DataSource.MEMORY_CACHE, DataSource.MEMORY -> otherFetches++
        }
    }

    fun recordError(decodeAttempted: Boolean) = record {
        requestErrors++
        if (decodeAttempted) decodeFailures++
    }

    private inline fun record(update: () -> Unit) {
        val message = lock.withLock {
            update()
            if (lastLoggedAt.elapsedNow().inWholeMilliseconds < LogIntervalMillis) return@withLock null
            lastLoggedAt = TimeSource.Monotonic.markNow()
            val memoryCache = imageLoader?.memoryCache
            "[ImageLoader] memoryHits=$memoryHits diskHits=$diskHits networkFetches=$networkFetches " +
                "otherFetches=$otherFetches errors=$requestErrors decodeFailures=$decodeFailures " +
                "memoryCacheEntries=${memoryCache?.keys?.size} " +
                "memoryCacheBytes=${memoryCache?.size}/${memoryCache?.maxSize}"
        }
        message?.let { cacheTraceLog { it } }
    }

    private const val LogIntervalMillis = 30_000L
}

/** fetch/decodeの発生有無から、メモリキャッシュ命中と各データソースからの取得を判定する。 */
private class MetricsEventListener : EventListener() {
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
        when (val dataSource = (result as? SourceFetchResult)?.dataSource ?: (result as? ImageFetchResult)?.dataSource) {
            null -> Unit
            else -> ImageLoaderMetrics.recordFetch(dataSource)
        }
    }

    override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
        decodeInvoked = true
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        // fetchStartが一度も呼ばれていなければ、フルパイプラインを経由しないメモリキャッシュ命中。
        if (!fetchInvoked) ImageLoaderMetrics.recordMemoryHit()
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        ImageLoaderMetrics.recordError(decodeAttempted = decodeInvoked)
    }
}

/**
 * 端末の利用可能メモリに相当する推定値(Androidは`ActivityManager`のmemoryClass、iOSは
 * デバイスの物理メモリ)。ここから算出した比率と[MemoryCacheMaximumBytes]の小さい方を使う。
 */
internal expect fun estimatedAvailableMemoryBytes(context: PlatformContext): Long

/** アプリ専用のキャッシュディレクトリ配下に画像キャッシュを置く(一時ディレクトリを使わない)。 */
internal expect fun appImageCacheDirectory(context: PlatformContext): Path

// 初期値。実機計測前のため、Android/iOSどちらも同じ絶対上限で頭打ちにする想定。
private const val MemoryCachePercentOfAvailable = 0.2
private const val MemoryCacheMaximumBytes = 64L * 1024 * 1024
private const val DiskCacheMaximumBytes = 128L * 1024 * 1024
private const val NetworkFetchConcurrency = 8
private const val DecodeConcurrency = 3
