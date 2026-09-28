package com.nostr.torinos.ui.settings

import androidx.compose.ui.platform.Clipboard
import kotlin.js.Promise
import kotlinx.coroutines.await

actual suspend fun Clipboard.setPlainText(text: String) {
    writeClipboardText(text).await<JsAny?>()
}

private fun writeClipboardText(text: String): Promise<JsAny?> =
    js("navigator.clipboard.writeText(text)")
