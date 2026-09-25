package com.nostr.torinos.ui.components

import android.graphics.Color
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
internal actual fun PlatformMediaPlayer(
    url: String,
    modifier: Modifier,
    onInfo: (MediaPlaybackInfo) -> Unit,
    onError: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnInfo by rememberUpdatedState(onInfo)
    val currentOnError by rememberUpdatedState(onError)
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = true
            prepare()
        }
    }
    val playerViewRef = remember(player) { PlayerViewRef() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.isEmpty) return
                val hasVideo = tracks.containsType(C.TRACK_TYPE_VIDEO)
                // 音声だけのメディアでは映像面が空になるため、操作部を常に表示しておく。
                playerViewRef.value?.apply {
                    controllerShowTimeoutMs = if (hasVideo) DefaultControllerTimeoutMs else 0
                    controllerHideOnTouch = hasVideo
                    if (!hasVideo) showController()
                }
                val size = player.videoSize
                currentOnInfo(
                    MediaPlaybackInfo(
                        hasVideo = hasVideo,
                        width = size.width.takeIf { hasVideo && it > 0 },
                        height = size.height.takeIf { hasVideo && it > 0 },
                    ),
                )
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                // 回転情報を含む表示上の比率で通知する。
                val width = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
                currentOnInfo(MediaPlaybackInfo(hasVideo = true, width = width, height = videoSize.height))
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnError()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    var isFullscreen by remember(player) { mutableStateOf(false) }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setFullscreenButtonClickListener { isFullscreen = true }
                playerViewRef.value = this
            }
        },
        update = { view ->
            // 同じ ExoPlayer を描画できる面は1つだけなので、全画面表示中はインライン側から外す。
            val target = if (isFullscreen) null else player
            if (view.player !== target) view.player = target
            playerViewRef.value = view
        },
        onRelease = { view ->
            view.player = null
            if (playerViewRef.value === view) playerViewRef.value = null
        },
        modifier = modifier,
    )

    if (isFullscreen) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        setBackgroundColor(Color.BLACK)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setFullscreenButtonState(true)
                        setFullscreenButtonClickListener { isFullscreen = false }
                        this.player = player
                    }
                },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private class PlayerViewRef {
    var value: PlayerView? = null
}

private const val DefaultControllerTimeoutMs = 5_000
