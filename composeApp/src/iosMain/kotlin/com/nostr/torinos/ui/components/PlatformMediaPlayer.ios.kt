package com.nostr.torinos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.asset
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.presentationSize
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.tracksWithMediaType
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL
import platform.UIKit.UIModalPresentationOverFullScreen

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun PlatformMediaPlayer(
    url: String,
    modifier: Modifier,
    onInfo: (MediaPlaybackInfo) -> Unit,
    onError: () -> Unit,
) {
    val currentOnInfo by rememberUpdatedState(onInfo)
    val currentOnError by rememberUpdatedState(onError)
    val item = remember(url) { NSURL.URLWithString(url)?.let { AVPlayerItem(uRL = it) } }
    if (item == null) {
        LaunchedEffect(url) { currentOnError() }
        return
    }
    val player = remember(item) { AVPlayer(playerItem = item) }
    var hasVideo by remember(item) { mutableStateOf(false) }
    val fullscreenRef = remember(player) { FullscreenControllerRef() }

    DisposableEffect(player) {
        // 消音スイッチがオンでも、ユーザーが明示的に再生したメディアの音は出す。
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        player.play()
        onDispose {
            fullscreenRef.value?.dismissViewControllerAnimated(false, completion = null)
            fullscreenRef.value = null
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
        }
    }

    // K/N から KVO を扱う複雑さを避け、読み込み完了までは状態を一定間隔で確認する。
    LaunchedEffect(item) {
        while (true) {
            when (item.status) {
                AVPlayerItemStatusFailed -> {
                    currentOnError()
                    return@LaunchedEffect
                }
                AVPlayerItemStatusReadyToPlay -> break
            }
            delay(StatusPollIntervalMillis)
        }
        @Suppress("DEPRECATION")
        hasVideo = item.asset.tracksWithMediaType(AVMediaTypeVideo).isNotEmpty()
        if (!hasVideo) {
            currentOnInfo(MediaPlaybackInfo(hasVideo = false, width = null, height = null))
            return@LaunchedEffect
        }
        // 映像の表示サイズは ReadyToPlay より少し遅れて確定することがある。
        repeat(PresentationSizePollCount) {
            val (width, height) = item.presentationSize.useContents { width.toInt() to height.toInt() }
            if (width > 0 && height > 0) {
                currentOnInfo(MediaPlaybackInfo(hasVideo = true, width = width, height = height))
                return@LaunchedEffect
            }
            delay(StatusPollIntervalMillis)
        }
        currentOnInfo(MediaPlaybackInfo(hasVideo = true, width = null, height = null))
    }

    Box(modifier = modifier) {
        UIKitViewController(
            factory = {
                AVPlayerViewController().apply {
                    this.player = player
                    showsPlaybackControls = true
                }
            },
            update = { controller ->
                if (controller.player !== player) controller.player = player
            },
            onRelease = { controller ->
                controller.player = null
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (hasVideo) {
            IconButton(
                onClick = {
                    val controller = AVPlayerViewController().apply {
                        this.player = player
                        // FullScreen だと下の Compose 画面が STOPPED になり、インライン側の解放で再生が止まる。
                        modalPresentationStyle = UIModalPresentationOverFullScreen
                    }
                    fullscreenRef.value = controller
                    topViewController()?.presentViewController(controller, animated = true, completion = null)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = "全画面表示",
                    tint = Color.White,
                )
            }
        }
    }
}

private class FullscreenControllerRef {
    var value: AVPlayerViewController? = null
}

private const val StatusPollIntervalMillis = 250L
private const val PresentationSizePollCount = 8
