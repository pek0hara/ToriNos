package com.nostr.torinos.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.ArticleItem
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.profile.AvatarCircle

@Composable
internal fun ArticleCard(
    article: ArticleItem,
    onClick: () -> Unit,
    onAuthorClick: (() -> Unit)?,
    onTopicClick: ((String) -> Unit)?,
) {
    val imageUrl = article.meta.imageUrl?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        imageUrl?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                NetworkImage(
                    url = it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    maxDecodeSizePx = 900,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.72f),
                                ),
                            ),
                        ),
                )
                Text(
                    text = article.displayTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
        if (imageUrl == null) {
            Text(
                text = article.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (article.displaySummary.isNotBlank()) {
            Text(
                text = article.displaySummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ArticleAuthorLine(article = article, onUserClick = onAuthorClick)
        if (article.meta.topics.isNotEmpty()) {
            ArticleTopics(
                topics = article.meta.topics.take(5),
                onTopicClick = onTopicClick,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun ArticleAuthorLine(
    article: ArticleItem,
    onUserClick: (() -> Unit)?,
) {
    Row(
        modifier = if (onUserClick != null) Modifier.clickable(onClick = onUserClick) else Modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvatarCircle(
            pubkey = article.event.pubkey,
            name = article.authorProfile?.bestName,
            pictureUrl = article.authorProfile?.picture,
            size = 24,
        )
        ProfileNameText(
            profile = article.authorProfile,
            fallback = article.event.shortPubkey,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatTimestamp(article.sortTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
