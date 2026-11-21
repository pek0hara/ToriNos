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
    onResult: (String?) -> Unit,
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
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(MAX_TEXT_FILE_BYTES + 1)
                        var size = 0
                        while (size < buffer.size) {
                            val read = input.read(buffer, size, buffer.size - size)
                            if (read < 0) break
                            size += read
                        }
                        if (size > MAX_TEXT_FILE_BYTES) null else buffer.decodeToString(0, size)
                    }
                }.onFailure { e ->
                    logException("TextFilePicker", e, "Failed to read picked file")
                }.getOrNull()
            }
            currentOnResult(text)
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}
