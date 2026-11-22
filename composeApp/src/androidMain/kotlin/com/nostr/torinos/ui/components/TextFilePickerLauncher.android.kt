package com.nostr.torinos.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nostr.torinos.util.logException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberTextFilePickerLauncher(
    onResult: (TextFilePickResult?) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            currentOnResult(null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(MAX_TEXT_FILE_BYTES + 1)
                        var size = 0
                        while (size < buffer.size) {
                            val read = input.read(buffer, size, buffer.size - size)
                            if (read < 0) break
                            size += read
                        }
                        // 上限を 1 バイト超えるまでしか読まないので、size は上限超えの判定にだけ使える。
                        textFilePickResult(size.toLong()) { buffer.decodeToString(0, size) }
                    } ?: TextFilePickResult.Unreadable
                }.onFailure { e ->
                    logException("TextFilePicker", e, "Failed to read picked file")
                }.getOrDefault(TextFilePickResult.Unreadable)
            }
            currentOnResult(result)
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}
