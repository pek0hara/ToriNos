package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get

// Web版の画像選択はブラウザのファイル選択を使う。プロフィール画像・バナー・記事の画像は選んだ画像をそのまま返し、
// 投稿・チャンネルの画像添付はネイティブと同じ大きさ・画質の JPEG に縮小してプレビューも作る。
// クリップボード画像は未対応のまま。

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
): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    return remember {
        {
            openOptimizedImageFilePicker(
                maxUploadDimension = MAX_UPLOAD_DIMENSION,
                uploadQuality = UPLOAD_JPEG_QUALITY,
                previewDimension = PREVIEW_DIMENSION,
                previewQuality = PREVIEW_JPEG_QUALITY,
                onPicked = { upload, preview ->
                    currentOnResult(
                        PickedImageData(
                            uploadBytes = upload.toByteArray(),
                            previewBytes = preview.toByteArray(),
                            mimeType = "image/jpeg",
                        ),
                    )
                },
                onCancel = { currentOnResult(null) },
            )
        }
    }
}

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

// ブラウザが縮小できない画像(デコードできない形式など)は、選択をキャンセルしたものとして扱う。
private fun openOptimizedImageFilePicker(
    maxUploadDimension: Int,
    uploadQuality: Double,
    previewDimension: Int,
    previewQuality: Double,
    onPicked: (Uint8Array, Uint8Array) -> Unit,
    onCancel: () -> Unit,
): Unit = js(
    """{
    const encodeJpeg = (image, maxDimension, quality) => new Promise((resolve, reject) => {
        const scale = Math.min(1, maxDimension / Math.max(image.naturalWidth, image.naturalHeight));
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
        canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
        const context = canvas.getContext('2d');
        // JPEG は透過を持てないので、透過部分は白にする(未指定だと黒になる)。
        context.fillStyle = '#fff';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.drawImage(image, 0, 0, canvas.width, canvas.height);
        canvas.toBlob((blob) => {
            if (!blob) { reject(new Error('toBlob failed')); return; }
            blob.arrayBuffer().then((buffer) => resolve(new Uint8Array(buffer)), reject);
        }, 'image/jpeg', quality);
    });
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
        // img 要素で読み込むと、ブラウザが EXIF の向きを反映してから描画する。
        const url = URL.createObjectURL(file);
        const image = new Image();
        image.src = url;
        image.decode()
            .then(() => Promise.all([
                encodeJpeg(image, maxUploadDimension, uploadQuality),
                encodeJpeg(image, previewDimension, previewQuality),
            ]))
            .then(([upload, preview]) => onPicked(upload, preview), () => onCancel())
            .finally(() => URL.revokeObjectURL(url));
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

private const val MAX_UPLOAD_DIMENSION = 2_048
private const val PREVIEW_DIMENSION = 256
private const val UPLOAD_JPEG_QUALITY = 0.85
private const val PREVIEW_JPEG_QUALITY = 0.8
