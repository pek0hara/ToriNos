package com.nostr.torinos.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.Image as SkiaImage
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetImageGeneratorSucceeded
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.valueWithCMTime
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.NSValue
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun loadVideoPreview(url: String): ImageBitmap? {
    val assetUrl = NSURL.URLWithString(url) ?: return null
    val generator = AVAssetImageGenerator(asset = AVURLAsset(uRL = assetUrl, options = null)).apply {
        appliesPreferredTrackTransform = true
        maximumSize = CGSizeMake(640.0, 640.0)
    }
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { generator.cancelAllCGImageGeneration() }
        generator.generateCGImagesAsynchronouslyForTimes(
            listOf(NSValue.valueWithCMTime(CMTimeMake(0, 1))),
        ) { _, image, _, result, _ ->
            val bitmap = if (result == AVAssetImageGeneratorSucceeded && image != null) {
                runCatching {
                    UIImagePNGRepresentation(UIImage.imageWithCGImage(image))
                        ?.toPreviewBytes()
                        ?.let { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }
                }.getOrNull()
            } else null
            if (continuation.isActive) continuation.resume(bitmap)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toPreviewBytes(): ByteArray {
    val result = ByteArray(length.toInt())
    result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length.convert()) }
    return result
}
