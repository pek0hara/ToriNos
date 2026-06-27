package com.nostr.torinos.ui.article

import androidx.compose.runtime.Composable

@Composable
expect fun ArticleEditorBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
