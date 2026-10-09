package com.nostr.torinos.ui.article

import androidx.compose.runtime.Composable

@Composable
expect fun ArticleEditorBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)

/** Release browser history interception after the article draft's relay save succeeds. */
internal expect fun releaseArticleEditorNavigationGuard()
