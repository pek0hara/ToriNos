package com.nostr.torinos.ui.components

// Web版は画像添付に対応していないため、呼ばれることはない。
actual suspend fun readImageDimensions(bytes: ByteArray): ImageDimensions? = null

actual suspend fun cropImageForUpload(
    bytes: ByteArray,
    cropRect: ImageCropRect,
    aspectRatio: Float,
): ByteArray? = null
