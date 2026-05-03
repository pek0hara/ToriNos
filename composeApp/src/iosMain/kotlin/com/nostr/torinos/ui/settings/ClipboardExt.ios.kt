package com.nostr.torinos.ui.settings

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.ExperimentalComposeUiApi

@OptIn(ExperimentalComposeUiApi::class)
actual suspend fun Clipboard.setPlainText(text: String) {
    setClipEntry(ClipEntry.withPlainText(text))
}
