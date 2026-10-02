package com.nostr.torinos.ui.components

import androidx.compose.ui.graphics.ImageBitmap

// Web版はインライン動画プレイヤー自体に未対応。
internal actual suspend fun loadVideoPreview(url: String): ImageBitmap? = null
