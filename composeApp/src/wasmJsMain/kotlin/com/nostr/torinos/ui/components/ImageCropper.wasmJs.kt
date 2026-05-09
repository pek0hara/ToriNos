package com.nostr.torinos.ui.components

actual suspend fun readImageDimensions(bytes: ByteArray): ImageDimensions? = null

actual suspend fun cropImageForUpload(
    bytes: ByteArray,
    cropRect: ImageCropRect,
    aspectRatio: Float,
): ByteArray? = null
