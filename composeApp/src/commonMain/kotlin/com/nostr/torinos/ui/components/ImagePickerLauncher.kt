package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable

/**
 * 画像ピッカーを起動するラムダを返す Composable。
 * [onResult] は (bytes, mimeType) を受け取る。選択キャンセル時は null。
 */
@Composable
expect fun rememberImagePickerLauncher(
    onResult: (ByteArray?, String?) -> Unit,
): () -> Unit
