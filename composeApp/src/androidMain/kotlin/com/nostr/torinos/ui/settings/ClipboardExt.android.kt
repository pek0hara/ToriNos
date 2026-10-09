package com.nostr.torinos.ui.settings

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

actual suspend fun Clipboard.setPlainText(text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText("plain text", text)))
}

actual suspend fun Clipboard.setSensitiveText(text: String) {
    val clip = ClipData.newPlainText("plain text", text).apply {
        // Android 13以降はコピー直後のプレビューで伏せ字になり、キーボードの履歴にも残りにくくなる。
        // 定数 ClipDescription.EXTRA_IS_SENSITIVE は API 33 からなので、同じ値を直接指定する。
        description.extras = PersistableBundle().apply { putBoolean(ExtraIsSensitive, true) }
    }
    setClipEntry(ClipEntry(clip))
}

private const val ExtraIsSensitive = "android.content.extra.IS_SENSITIVE"
