package com.nostr.torinos.ui.settings

import androidx.compose.ui.platform.Clipboard

expect suspend fun Clipboard.setPlainText(text: String)
