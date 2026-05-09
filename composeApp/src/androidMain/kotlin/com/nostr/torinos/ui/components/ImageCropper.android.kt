package com.nostr.torinos.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

actual suspend fun readImageDimensions(bytes: ByteArray): ImageDimensions? = withContext(Dispatchers.IO) {
    runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            ImageDimensions(options.outWidth, options.outHeight)
        } else {
            null
        }
    }.getOrNull()
}

actual suspend fun cropImageForUpload(
    bytes: ByteArray,
    cropRect: ImageCropRect,
    aspectRatio: Float,
): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        try {
            val left = (source.width * cropRect.left).roundToInt().coerceIn(0, source.width - 1)
            val top = (source.height * cropRect.top).roundToInt().coerceIn(0, source.height - 1)
            val width = (source.width * cropRect.width).roundToInt().coerceIn(1, source.width - left)
            val height = (source.height * cropRect.height).roundToInt().coerceIn(1, source.height - top)
            val cropped = Bitmap.createBitmap(source, left, top, width, height)
            try {
                val targetWidth = TARGET_UPLOAD_WIDTH
                val targetHeight = (TARGET_UPLOAD_WIDTH / aspectRatio).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
                try {
                    ByteArrayOutputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        out.toByteArray()
                    }
                } finally {
                    scaled.recycle()
                }
            } finally {
                cropped.recycle()
            }
        } finally {
            source.recycle()
        }
    }.getOrNull()
}

private const val TARGET_UPLOAD_WIDTH = 1_200
private const val JPEG_QUALITY = 90
