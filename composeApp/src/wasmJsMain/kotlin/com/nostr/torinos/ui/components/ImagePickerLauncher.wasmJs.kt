package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get

// Web版はプロフィール画像・バナー・記事の画像だけ対応する。ブラウザのファイル選択で選んだ画像をそのまま返す。
// 投稿・チャンネルの画像添付（縮小とプレビュー生成が必要）とクリップボード画像は未対応のまま。

@Composable
actual fun rememberImagePickerLauncher(
    onResult: (ByteArray?, String?) -> Unit,
): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    return remember {
        {
            // ブラウザはユーザー操作中にしかファイル選択を開かないので、タップの処理中に同期的に開く。
            openImageFilePicker(
                onPicked = { data, mimeType -> currentOnResult(data.toByteArray(), mimeType) },
                onCancel = { currentOnResult(null, null) },
            )
        }
    }
}

@Composable
actual fun rememberOptimizedImagePickerLauncher(
    onResult: (PickedImageData?) -> Unit,
): () -> Unit = {}

@Composable
actual fun rememberClipboardImageReader(
    onResult: (PickedImageData?) -> Unit,
): () -> Unit = {}

private fun openImageFilePicker(
    onPicked: (Uint8Array, String) -> Unit,
    onCancel: () -> Unit,
): Unit = js(
    """{
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.style.display = 'none';
    let settled = false;
    const settle = () => {
        if (settled) return false;
        settled = true;
        input.remove();
        return true;
    };
    input.addEventListener('change', () => {
        const file = input.files && input.files[0];
        if (!settle()) return;
        if (!file) { onCancel(); return; }
        file.arrayBuffer().then(
            (buffer) => onPicked(new Uint8Array(buffer), file.type || 'application/octet-stream'),
            () => onCancel(),
        );
    });
    input.addEventListener('cancel', () => { if (settle()) onCancel(); });
    document.body.appendChild(input);
    input.click();
}"""
)

private fun Uint8Array.toByteArray(): ByteArray {
    val bytes = Int8Array(buffer, byteOffset, length)
    return ByteArray(length) { bytes[it] }
}
