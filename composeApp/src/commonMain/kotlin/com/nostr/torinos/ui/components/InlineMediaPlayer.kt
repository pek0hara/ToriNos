package com.nostr.torinos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nostr.torinos.model.MediaKind
import com.nostr.torinos.model.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** 読み込み後にプラットフォームのプレイヤーから受け取るメディア情報。 */
data class MediaPlaybackInfo(
    val hasVideo: Boolean,
    val width: Int?,
    val height: Int?,
)

/**
 * プラットフォームの動画・音声プレイヤー。コンポジションに入った時点で読み込みと再生を始め、
 * 外れた時点で解放する。
 */
@Composable
internal expect fun PlatformMediaPlayer(
    url: String,
    modifier: Modifier,
    onInfo: (MediaPlaybackInfo) -> Unit,
    onError: () -> Unit,
)

/** 同時に生成するプレイヤーを1つに保つ。 */
class MediaPlaybackCoordinator {
    private val _activeKey = MutableStateFlow<String?>(null)
    val activeKey: StateFlow<String?> = _activeKey.asStateFlow()

    fun activate(key: String) {
        _activeKey.value = key
    }

    /** 後から有効になった別のプレイヤーを止めないよう、キーが一致するときだけ解除する。 */
    fun deactivate(key: String) {
        _activeKey.compareAndSet(key, null)
    }
}

val ActiveMediaPlayback = MediaPlaybackCoordinator()

/** 同じURLが別の投稿カードに出ても区別できるよう、表示元と組にする。 */
fun mediaPlaybackKey(sourceId: String, url: String): String = "$sourceId\n$url"

internal fun inlineMediaAspectRatio(width: Int?, height: Int?): Float {
    if (width == null || height == null || width <= 0 || height <= 0) return DefaultAspectRatio
    return (width.toFloat() / height.toFloat()).coerceIn(MinAspectRatio, MaxAspectRatio)
}

internal fun inlineMediaHeight(containerWidth: Dp, aspectRatio: Float, maxHeight: Dp): Dp =
    minOf(containerWidth / aspectRatio, maxHeight)

@Composable
internal fun InlineMediaPlayer(
    media: MediaMetadata,
    sourceId: String,
    showPoster: Boolean,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 360.dp,
) {
    val key = remember(sourceId, media.url) { mediaPlaybackKey(sourceId, media.url) }
    val activeKey by ActiveMediaPlayback.activeKey.collectAsState()
    val isActive = activeKey == key
    var info by remember(key) { mutableStateOf<MediaPlaybackInfo?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    val isAudio = media.mediaKind == MediaKind.Audio || info?.hasVideo == false
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uriHandler = LocalUriHandler.current

    DisposableEffect(key) {
        onDispose { ActiveMediaPlayback.deactivate(key) }
    }
    if (isActive) {
        LaunchedEffect(key, lifecycle) {
            lifecycle.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.STARTED) }
            ActiveMediaPlayback.deactivate(key)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val playerHeight = if (isAudio) {
            AudioPlayerHeight
        } else {
            inlineMediaHeight(
                containerWidth = maxWidth,
                aspectRatio = inlineMediaAspectRatio(
                    width = info?.width ?: media.width,
                    height = info?.height ?: media.height,
                ),
                maxHeight = maxHeight,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(playerHeight)
                .clip(MaterialTheme.shapes.small)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isActive -> PlatformMediaPlayer(
                    url = media.url,
                    modifier = Modifier.fillMaxSize(),
                    onInfo = { info = it },
                    onError = {
                        failed = true
                        ActiveMediaPlayback.deactivate(key)
                    },
                )
                failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            failed = false
                            ActiveMediaPlayback.activate(key)
                        },
                ) {
                    Text(
                        text = "このメディアは再生できません",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                    )
                    TextButton(onClick = { uriHandler.openUri(media.url) }) {
                        Text("ブラウザで開く")
                    }
                }
                else -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { ActiveMediaPlayback.activate(key) },
                ) {
                    val posterUrl = media.posterUrl
                    if (!isAudio && showPoster && posterUrl != null) {
                        NetworkImage(
                            url = posterUrl,
                            contentDescription = media.alt,
                            contentScale = ContentScale.Fit,
                            blurHash = media.blurhash,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (!isAudio && showPoster) {
                        VideoPreview(
                            url = media.url,
                            contentDescription = media.alt,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (isAudio) {
                            Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = if (isAudio) "音声を再生" else "動画を再生",
                            tint = Color.White,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f)),
                        )
                    }
                }
            }
        }
    }
}

private const val DefaultAspectRatio = 16f / 9f
private const val MinAspectRatio = 9f / 16f
private const val MaxAspectRatio = 2f
private val AudioPlayerHeight = 120.dp
