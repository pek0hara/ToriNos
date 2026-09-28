package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

// Web版はインライン再生に対応していない。失敗として通知し、呼び出し側のリンク表示に任せる。
@Composable
internal actual fun PlatformMediaPlayer(
    url: String,
    modifier: Modifier,
    onInfo: (MediaPlaybackInfo) -> Unit,
    onError: () -> Unit,
) {
    val currentOnError by rememberUpdatedState(onError)
    LaunchedEffect(url) { currentOnError() }
}
