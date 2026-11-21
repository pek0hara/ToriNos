package com.nostr.torinos.ui.post

import androidx.compose.ui.Modifier

internal actual fun Modifier.imagePasteMenuItem(
    enabled: Boolean,
    onPasteImage: () -> Unit,
): Modifier = this
