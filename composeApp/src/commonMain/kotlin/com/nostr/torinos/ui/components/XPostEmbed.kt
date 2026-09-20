package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap

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
 * 画像が際限なく残らないよう、直近の投稿だけをLRU方式で保持する。
 */
internal object XPostSnapshotCache {
    private const val MaximumEntries = 8
    private val entries = LinkedHashMap<XPostSnapshotCacheKey, ImageBitmap>()

    operator fun get(key: XPostSnapshotCacheKey): ImageBitmap? =
        entries.remove(key)?.also { image -> entries[key] = image }

    operator fun set(key: XPostSnapshotCacheKey, image: ImageBitmap) {
        entries.remove(key)
        entries[key] = image
        while (entries.size > MaximumEntries) {
            entries.remove(entries.keys.first())
        }
    }
}
