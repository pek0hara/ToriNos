package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable

data class PickedImageData(
    val uploadBytes: ByteArray,
    val previewBytes: ByteArray,
    val mimeType: String,
)

/**
 * 画像ピッカーを起動するラムダを返す Composable。
 * [onResult] は (bytes, mimeType) を受け取る。選択キャンセル時は null。
 */
@Composable
expect fun rememberImagePickerLauncher(
    onResult: (ByteArray?, String?) -> Unit,
): () -> Unit

/**
 * アップロード用画像と、画面表示用の軽量なプレビューを返す画像ピッカー。
 */
@Composable
expect fun rememberOptimizedImagePickerLauncher(
    onResult: (PickedImageData?) -> Unit,
): () -> Unit

/**
 * クリップボードの先頭にある画像を、アップロード用とプレビュー用に変換して返す。
 * 画像がない場合や読み取れない場合は null を返す。
 */
@Composable
expect fun rememberClipboardImageReader(
    onResult: (PickedImageData?) -> Unit,
): () -> Unit
