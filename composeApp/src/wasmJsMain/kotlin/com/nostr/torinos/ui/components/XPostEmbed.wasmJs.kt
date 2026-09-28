package com.nostr.torinos.ui.components

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler

// Web版は埋め込み表示をせず、元の投稿を開くボタンだけを出す。
@Composable
internal actual fun XPostEmbed(
    postId: String,
    sourceUrl: String,
    darkTheme: Boolean,
    modifier: Modifier,
    deferLoad: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    TextButton(
        onClick = { uriHandler.openUri(sourceUrl) },
        modifier = modifier,
    ) {
        Text("X の投稿を開く")
    }
}
