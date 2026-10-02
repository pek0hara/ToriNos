package com.nostr.torinos.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 再生や音声出力をせずに、動画の先頭フレームを取得する。 */
internal expect suspend fun loadVideoPreview(url: String): ImageBitmap?

// フィードのスクロールで取得処理を大量に起動しない。成功・失敗ともに最大24件記憶する。
private val previewLock = Mutex()
private val previewCache = linkedMapOf<String, ImageBitmap?>()

@Composable
internal fun VideoPreview(url: String, contentDescription: String?, modifier: Modifier) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = previewLock.withLock {
            if (previewCache.containsKey(url)) {
                val cached = previewCache.remove(url)
                previewCache[url] = cached
                cached
            } else {
                val loaded = withTimeoutOrNull(10_000) { loadVideoPreview(url) }
                previewCache[url] = loaded
                if (previewCache.size > 24) previewCache.remove(previewCache.keys.first())
                loaded
            }
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}
