package com.nostr.torinos.ui.article

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.sp
import com.nostr.torinos.article.ArticleEngagementSummary
import com.nostr.torinos.article.OwnArticleReaction
import com.nostr.torinos.model.ReactionOption
import kotlinx.coroutines.flow.StateFlow
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
    engagement: StateFlow<ArticleEngagementSummary?>? = null,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 件数を右端に必要な幅だけ置き、残りの幅を著者行に渡す。著者行のタップ範囲は中身の幅に留める。
            Box(modifier = Modifier.weight(1f)) {
                ArticleAuthorLine(article = article, onUserClick = onAuthorClick)
            }
            if (engagement != null) {
                ArticleCardEngagement(engagement)
            }
        }
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = if (onUserClick != null) modifier.clickable(onClick = onUserClick) else modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvatarCircle(
            pubkey = article.event.pubkey,
            name = article.authorProfile?.bestName,
            pictureUrl = article.authorProfile?.picture,
            size = 24,
        )
        // 名前が長い場合は名前を省略し、日時は折り返さない。
        ProfileNameText(
            profile = article.authorProfile,
            fallback = article.event.shortPubkey,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = formatTimestamp(article.sortTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * カード右端のリアクション数とコメント数。自分の記事の件数だけを購読し、
 * 件数が届いても行の高さを変えない。タップはカード本体（記事詳細）に任せる。
 */
@Composable
private fun ArticleCardEngagement(engagement: StateFlow<ArticleEngagementSummary?>) {
    val summary by engagement.collectAsState()
    val current = summary ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (current.reactionCount > 0 || current.ownReaction != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                when (val own = current.ownReaction) {
                    is OwnArticleReaction.Emoji -> when (val option = own.option) {
                        is ReactionOption.Custom -> NetworkImage(
                            url = option.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(14.dp),
                        )
                        is ReactionOption.Unicode -> Text(text = option.value, fontSize = 12.sp, maxLines = 1)
                    }
                    else -> Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        tint = if (own == OwnArticleReaction.Like) OwnReactionTint else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                EngagementCountText(current.reactionCount, current.isReactionLowerBound)
            }
        }
        if (current.commentCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    Icons.Default.MailOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                EngagementCountText(current.commentCount, current.isCommentLowerBound)
            }
        }
    }
}

@Composable
private fun EngagementCountText(count: Int, isLowerBound: Boolean) {
    Text(
        text = if (isLowerBound) "$count+" else count.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 投稿カードで自分のリアクションを示す色と同じ。 */
private val OwnReactionTint = Color(0xFFE17055)
