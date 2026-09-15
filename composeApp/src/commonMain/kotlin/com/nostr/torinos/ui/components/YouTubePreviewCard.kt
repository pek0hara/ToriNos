package com.nostr.torinos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.network.YouTubePreviewRepository
import io.ktor.http.Url

private val youTubeVideoIdRegex = Regex("^[A-Za-z0-9_-]{11}$")

internal fun extractYouTubeVideoId(url: String): String? {
    val parsed = runCatching { Url(url.trim()) }.getOrNull() ?: return null
    if (parsed.protocol.name != "http" && parsed.protocol.name != "https") return null
    if (parsed.user != null || parsed.password != null) return null

    val host = parsed.host.lowercase()
    val segments = parsed.segments
    val videoId = when {
        host == "youtu.be" -> segments.firstOrNull()
        host in youTubeHosts && segments == listOf("watch") -> parsed.parameters["v"]
        host in youTubeHosts && segments.firstOrNull() in youTubeVideoPaths -> segments.getOrNull(1)
        host == "youtube-nocookie.com" || host == "www.youtube-nocookie.com" -> {
            if (segments.firstOrNull() == "embed") segments.getOrNull(1) else null
        }
        else -> null
    }

    return videoId?.takeIf { youTubeVideoIdRegex.matches(it) }
}

internal fun youTubeThumbnailUrl(videoId: String): String =
    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

@Composable
internal fun YouTubePreviewCard(
    videoId: String,
    sourceUrl: String,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val title by produceState<String?>(initialValue = null, key1 = videoId) {
        value = YouTubePreviewRepository.fetchTitle(videoId)
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clip(shape)
            .background(Color.Black)
            .clickable { runCatching { uriHandler.openUri(sourceUrl) } }
            .semantics { contentDescription = "YouTubeで動画を開く" },
        contentAlignment = Alignment.Center,
    ) {
        NetworkImage(
            url = youTubeThumbnailUrl(videoId),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            maxDecodeSizePx = YouTubeThumbnailMaxDecodeSizePx,
            filterQuality = FilterQuality.Low,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .size(width = 68.dp, height = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(YouTubeRed),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(34.dp),
            )
        }
        title?.let {
            Text(
                text = it,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        ),
                    )
                    .padding(start = 12.dp, top = 28.dp, end = 12.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val youTubeHosts = setOf(
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "music.youtube.com",
)
private val youTubeVideoPaths = setOf("shorts", "embed", "live")
private val YouTubeRed = Color(0xFFFF0000)
private const val YouTubeThumbnailMaxDecodeSizePx = 720
