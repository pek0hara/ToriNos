package com.nostr.torinos.ui.components

// 切り抜きダイアログ(ImageCropperDialog)は今どこからも使っていない。
// Web版で使うときは canvas で切り抜く実装に置き換える。
actual suspend fun readImageDimensions(bytes: ByteArray): ImageDimensions? = null

actual suspend fun cropImageForUpload(
    bytes: ByteArray,
    cropRect: ImageCropRect,
    aspectRatio: Float,
): ByteArray? = null
