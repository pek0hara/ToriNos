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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
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
) {
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
