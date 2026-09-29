package com.nostr.torinos.ui.post

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun FeedInlineComposerBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onBack)
}
