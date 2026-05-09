package com.nostr.torinos.ui.components

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import kotlin.math.roundToInt

@OptIn(ExperimentalForeignApi::class)
actual suspend fun readImageDimensions(bytes: ByteArray): ImageDimensions? {
    val image = UIImage(data = bytes.toNSData())
    val (width, height) = image.size.useContents { width.roundToInt() to height.roundToInt() }
    return if (width > 0 && height > 0) ImageDimensions(width, height) else null
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun cropImageForUpload(
    bytes: ByteArray,
    cropRect: ImageCropRect,
    aspectRatio: Float,
): ByteArray? {
    val image = UIImage(data = bytes.toNSData())
    val (imageWidth, imageHeight) = image.size.useContents { width to height }
    if (imageWidth <= 0.0 || imageHeight <= 0.0) return null

    val cropWidth = imageWidth * cropRect.width
    val cropHeight = imageHeight * cropRect.height
    if (cropWidth <= 0.0 || cropHeight <= 0.0) return null

    val targetWidth = TARGET_UPLOAD_WIDTH.toDouble()
    val targetHeight = targetWidth / aspectRatio
    val scale = targetWidth / cropWidth
    val drawLeft = -(imageWidth * cropRect.left) * scale
    val drawTop = -(imageHeight * cropRect.top) * scale

    UIGraphicsBeginImageContextWithOptions(
        platform.CoreGraphics.CGSizeMake(targetWidth, targetHeight),
        true,
        1.0,
    )
    image.drawInRect(
        CGRectMake(
            drawLeft,
            drawTop,
            imageWidth * scale,
            imageHeight * scale,
        ),
    )
    val cropped = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()

    return cropped?.let { UIImageJPEGRepresentation(it, 0.9)?.toByteArray() }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    return ByteArray(length).also { bytes ->
        bytes.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), this.bytes, this.length)
        }
    }
}

private const val TARGET_UPLOAD_WIDTH = 1_200
