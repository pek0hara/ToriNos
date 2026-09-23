package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.nostr.torinos.util.cacheTraceLog

private val xPostUrlRegex = Regex(
    pattern = """^https?://(?:(?:www|mobile)\.)?(?:x\.com|twitter\.com)/[^/?#]+/status/(\d+)(?:[/?#].*)?$""",
    option = RegexOption.IGNORE_CASE,
)

internal fun extractXPostId(url: String): String? =
    xPostUrlRegex.matchEntire(url.trim())?.groupValues?.getOrNull(1)

internal fun xPostEmbedUrl(
    postId: String,
    darkTheme: Boolean,
): String = buildString {
    append("https://platform.twitter.com/embed/Tweet.html?id=")
    append(postId)
    append("&dnt=true&theme=")
    append(if (darkTheme) "dark" else "light")
}

@Composable
internal expect fun XPostEmbed(
    postId: String,
    sourceUrl: String,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    /** true の間はスナップショットキャッシュ命中時を除き WebView を新規生成しない。 */
    deferLoad: Boolean = false,
)

internal data class XPostSnapshotCacheKey(
    val postId: String,
    val darkTheme: Boolean,
    val widthPx: Int,
)

/**
 * タイムライン項目が破棄・再生成されても、同じ投稿のWebViewを読み直さずに済むようにする。
 * 画像が際限なく残らないよう、件数上限と推定デコード後バイト数の総量上限を併用したLRUで保持する。
 * 実画像の内部表現が推定値より大きい可能性があるため、バイト上限とは別に件数上限も維持する。
 */
internal object XPostSnapshotCache {
    private const val MaximumEntries = 8
    private const val MaximumSingleImageBytes = 8L * 1024 * 1024
    private const val MaximumTotalBytes = 24L * 1024 * 1024

    private class Entry(val image: ImageBitmap, val estimatedBytes: Long)

    private val entries = LinkedHashMap<XPostSnapshotCacheKey, Entry>()
    private var totalBytes = 0L

    operator fun get(key: XPostSnapshotCacheKey): ImageBitmap? {
        val entry = entries.remove(key)
        if (entry == null) {
            cacheTraceLog { "[XPostSnapshotCache] miss postId=${key.postId} size=${entries.size}" }
            return null
        }
        entries[key] = entry
        cacheTraceLog { "[XPostSnapshotCache] hit postId=${key.postId} size=${entries.size}" }
        return entry.image
    }

    operator fun set(key: XPostSnapshotCacheKey, image: ImageBitmap) {
        val estimatedBytes = image.width.toLong() * image.height.toLong() * 4L
        if (estimatedBytes > MaximumSingleImageBytes) {
            cacheTraceLog {
                "[XPostSnapshotCache] rejected postId=${key.postId} estimatedBytes=$estimatedBytes " +
                    "(over single-image limit $MaximumSingleImageBytes)"
            }
            return
        }
        entries.remove(key)?.let { totalBytes -= it.estimatedBytes }
        entries[key] = Entry(image, estimatedBytes)
        totalBytes += estimatedBytes
        while (entries.size > MaximumEntries || totalBytes > MaximumTotalBytes) {
            val oldestKey = entries.keys.firstOrNull() ?: break
            totalBytes -= (entries.remove(oldestKey)?.estimatedBytes ?: 0L)
            cacheTraceLog {
                "[XPostSnapshotCache] evicted postId=${oldestKey.postId} " +
                    "size=${entries.size} totalBytes=$totalBytes"
            }
        }
        cacheTraceLog {
            "[XPostSnapshotCache] set postId=${key.postId} estimatedBytes=$estimatedBytes " +
                "size=${entries.size} totalBytes=$totalBytes"
        }
    }

    /** OSのメモリ警告(Androidのトリム通知、iOSのmemory warning)を受けて全件破棄する。 */
    fun clear() {
        cacheTraceLog { "[XPostSnapshotCache] clear (memory pressure) size=${entries.size}" }
        entries.clear()
        totalBytes = 0L
    }
}
