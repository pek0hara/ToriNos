package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable

@Composable
actual fun rememberImagePickerLauncher(
    onResult: (ByteArray?, String?) -> Unit,
): () -> Unit = { /* iOS 未実装 */ }
