package com.nostr.torinos.ui.article

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun ArticleEditorBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onBack)
}
