package com.nostr.torinos.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
actual fun rememberImagePickerLauncher(
    onResult: (ByteArray?, String?) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            onResult(null, null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val (bytes, mime) = withContext(Dispatchers.IO) {
                loadImageBytesForUpload(context, uri)
            }
            onResult(bytes, mime)
        }
    }

    return { launcher.launch("image/*") }
}

private fun loadImageBytesForUpload(
    context: android.content.Context,
    uri: Uri,
): Pair<ByteArray?, String?> = runCatching {
    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"

    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    } ?: return null to null

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null to null
        return raw to mimeType
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds, MAX_UPLOAD_DIMENSION)
    }
    val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, decodeOptions)
    } ?: return null to null

    try {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        out.toByteArray() to "image/jpeg"
    } finally {
        bitmap.recycle()
    }
}.getOrElse {
    null to null
}

private fun calculateInSampleSize(options: BitmapFactory.Options, maxDimension: Int): Int {
    val (width, height) = options.outWidth to options.outHeight
    var sampleSize = 1

    while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
        sampleSize *= 2
    }

    return sampleSize
}

private const val MAX_UPLOAD_DIMENSION = 2_048
private const val JPEG_QUALITY = 90
