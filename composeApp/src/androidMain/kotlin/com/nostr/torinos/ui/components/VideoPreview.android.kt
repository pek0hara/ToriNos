package com.nostr.torinos.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun loadVideoPreview(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(url, emptyMap())
        val frame = if (Build.VERSION.SDK_INT >= 27) {
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
        } else {
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { original ->
                val scale = minOf(1f, 640f / maxOf(original.width, original.height))
                val scaled = Bitmap.createScaledBitmap(
                    original,
                    (original.width * scale).toInt().coerceAtLeast(1),
                    (original.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== original) original.recycle()
                scaled
            }
        }
        frame?.asImageBitmap()
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    }
}
