package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable

@Composable
actual fun rememberImagePickerLauncher(
    onResult: (ByteArray?, String?) -> Unit,
): () -> Unit = { /* wasmJs 未実装 */ }

@Composable
actual fun rememberOptimizedImagePickerLauncher(
    onResult: (PickedImageData?) -> Unit,
): () -> Unit = { /* wasmJs 未実装 */ }
