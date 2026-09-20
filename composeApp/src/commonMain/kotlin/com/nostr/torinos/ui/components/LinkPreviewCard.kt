package com.nostr.torinos.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.network.LinkPreview
import com.nostr.torinos.network.LinkPreviewRepository

@Composable
fun LinkPreviewCard(
    url: String,
    modifier: Modifier = Modifier,
    deferWebViewLoad: Boolean = false,
    showXPreview: Boolean = true,
    showImagePreview: Boolean = true,
) {
    val youTubeVideoId = extractYouTubeVideoId(url)
    if (youTubeVideoId != null) {
        var revealed by remember(url) { mutableStateOf(false) }
        Spacer(modifier = Modifier.height(8.dp))
        if (showImagePreview || revealed) {
            YouTubePreviewCard(
                videoId = youTubeVideoId,
                sourceUrl = url,
                modifier = modifier,
            )
        } else {
            PreviewLoadPlaceholder(
                label = "YouTubeのプレビュー",
                onReveal = { revealed = true },
                modifier = modifier,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        return
    }

    val xPostId = extractXPostId(url)
    if (xPostId != null) {
        var revealed by remember(url) { mutableStateOf(false) }
        Spacer(modifier = Modifier.height(8.dp))
        if (showXPreview || revealed) {
            val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
            XPostEmbed(
                postId = xPostId,
                sourceUrl = url,
                darkTheme = darkTheme,
                deferLoad = deferWebViewLoad,
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            PreviewLoadPlaceholder(
                label = "Xの投稿プレビュー",
                onReveal = { revealed = true },
                modifier = modifier,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        return
    }

    var genericPreviewRevealed by remember(url) { mutableStateOf(false) }
    if (!showImagePreview && !genericPreviewRevealed) {
        Spacer(modifier = Modifier.height(8.dp))
        PreviewLoadPlaceholder(
            label = "リンクプレビュー",
            onReveal = { genericPreviewRevealed = true },
            modifier = modifier,
        )
        return
    }

    val previewState by produceState<LinkPreviewState>(LinkPreviewState.Loading, url) {
        value = LinkPreviewRepository.fetch(url)?.let(LinkPreviewState::Loaded)
            ?: LinkPreviewState.Unavailable
    }

    when (val state = previewState) {
        LinkPreviewState.Loading -> Unit
        LinkPreviewState.Unavailable -> Unit
        is LinkPreviewState.Loaded -> {
            Spacer(modifier = Modifier.height(8.dp))
            PreviewCard(state.preview, modifier)
        }
    }
}

@Composable
private fun PreviewCard(
    preview: LinkPreview,
    modifier: Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.small,
            )
            .clickable { uriHandler.openUri(preview.url) }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        preview.imageUrl?.let { imageUrl ->
            NetworkImage(
                url = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                maxDecodeSizePx = LinkPreviewImageMaxDecodeSizePx,
                filterQuality = FilterQuality.Low,
                modifier = Modifier
                    .size(width = 88.dp, height = 66.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            preview.siteName?.let { siteName ->
                Text(
                    text = siteName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = preview.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            preview.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
}

private sealed interface LinkPreviewState {
    data object Loading : LinkPreviewState
    data object Unavailable : LinkPreviewState
    data class Loaded(val preview: LinkPreview) : LinkPreviewState
}

private const val LinkPreviewImageMaxDecodeSizePx = 256
