package com.nostr.torinos.ui.post

import androidx.compose.runtime.Composable

/** 簡易投稿欄が開いている間のシステムBack。Androidのみ有効で、iOSとWebでは何もしない。 */
@Composable
expect fun FeedInlineComposerBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
