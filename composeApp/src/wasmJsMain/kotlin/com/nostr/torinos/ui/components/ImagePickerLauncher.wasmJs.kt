package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable

// Web版は画像添付に対応していない。ピッカーを開かず、結果も返さない。

@Composable
actual fun rememberImagePickerLauncher(
    onResult: (ByteArray?, String?) -> Unit,
): () -> Unit = {}

@Composable
actual fun rememberOptimizedImagePickerLauncher(
    onResult: (PickedImageData?) -> Unit,
): () -> Unit = {}

@Composable
actual fun rememberClipboardImageReader(
    onResult: (PickedImageData?) -> Unit,
): () -> Unit = {}
