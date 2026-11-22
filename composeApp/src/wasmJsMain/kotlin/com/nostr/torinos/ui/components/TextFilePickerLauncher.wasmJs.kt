package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@Composable
actual fun rememberTextFilePickerLauncher(
    onResult: (TextFilePickResult?) -> Unit,
): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    return remember {
        {
            // ブラウザはユーザー操作中にしかファイル選択を開かないので、タップの処理中に同期的に開く。
            openTextFilePicker(
                maxBytes = MAX_TEXT_FILE_BYTES,
                onPicked = { text -> currentOnResult(TextFilePickResult.Picked(text)) },
                onTooLarge = { currentOnResult(TextFilePickResult.TooLarge) },
                onUnreadable = { currentOnResult(TextFilePickResult.Unreadable) },
                onCancel = { currentOnResult(null) },
            )
        }
    }
}

private fun openTextFilePicker(
    maxBytes: Int,
    onPicked: (String) -> Unit,
    onTooLarge: () -> Unit,
    onUnreadable: () -> Unit,
    onCancel: () -> Unit,
): Unit = js(
    """{
    const input = document.createElement('input');
    input.type = 'file';
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
        if (file.size > maxBytes) { onTooLarge(); return; }
        file.text().then((text) => onPicked(text), () => onUnreadable());
    });
    input.addEventListener('cancel', () => { if (settle()) onCancel(); });
    document.body.appendChild(input);
    input.click();
}"""
)
