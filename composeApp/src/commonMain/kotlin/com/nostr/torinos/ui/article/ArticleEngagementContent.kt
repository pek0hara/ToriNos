package com.nostr.torinos.ui.article

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.emoji.customEmojiMap
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.engagement.displayOwnEmojiReactionEventIds
import com.nostr.torinos.engagement.hasOwnReaction
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.ReactionSummaryRow
import com.nostr.torinos.ui.components.StandardEmojiPickerSheet
import com.nostr.torinos.ui.components.extractImageUrls
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.components.stripImageUrls
import com.nostr.torinos.ui.profile.AvatarCircle

/** 記事詳細の本文の下に、リアクション集計とコメント一覧を並べる。 */
internal fun LazyListScope.articleEngagementItems(
    engagement: ArticleEngagementState,
    onUserClick: (pubkey: String) -> Unit,
    reactionActions: ArticleReactionActions?,
    onComment: () -> Unit,
) {
    item(key = "engagement-divider", contentType = "divider") {
        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
    }
    when (engagement) {
        ArticleEngagementState.Loading -> item(key = "engagement-loading", contentType = "loading") {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
        }
        is ArticleEngagementState.Failed -> item(key = "engagement-failed", contentType = "message") {
            EngagementMessage(text = engagement.message, isError = true)
        }
        is ArticleEngagementState.Loaded -> {
            item(key = "engagement-reactions", contentType = "reactions") {
                ArticleReactions(
                    reactions = engagement.reactions,
                    error = engagement.reactionError,
                    actions = reactionActions,
                )
            }
            item(key = "engagement-comments-header", contentType = "section-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "コメント ${engagement.comments.size}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onComment) {
                        Text("コメントする")
                    }
                }
            }
            if (engagement.comments.isEmpty()) {
                item(key = "engagement-comments-empty", contentType = "message") {
                    EngagementMessage(text = "コメントはまだありません")
                }
            } else {
                items(
                    items = engagement.comments,
                    key = { "comment-${it.id}" },
                    contentType = { "comment" },
                ) { comment ->
                    ArticleCommentItem(
                        event = comment,
                        profile = engagement.commentProfiles[comment.pubkey],
                        onUserClick = onUserClick,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}

/** リアクション操作。未ログイン時はnullにして表示だけを行う。 */
internal class ArticleReactionActions(
    val onLike: () -> Unit,
    val onUnlike: () -> Unit,
    val onReact: (ReactionOption) -> Unit,
    val onUnreact: (ReactionOption) -> Unit,
)

@Composable
private fun ArticleReactions(
    reactions: NoteEngagementState,
    error: String?,
    actions: ArticleReactionActions?,
) {
    var showStandardEmojiPicker by remember { mutableStateOf(false) }
    val isLiked = reactions.ownLikeEventId != null ||
        reactions.pendingOperations[EngagementSlot.Reaction]?.request == EngagementRequest.AddLike
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (reactions.reactionCount == 0 && actions == null) {
            Text(
                text = "リアクションはまだありません",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ReactionSummaryRow(
                totalReactionCount = reactions.reactionCount,
                explicitLikeCount = reactions.likeReactionCount,
                isLiked = isLiked,
                customReactions = reactions.customReactions,
                unicodeReactions = reactions.unicodeReactions,
                ownEmojiReactionEventIds = reactions.displayOwnEmojiReactionEventIds,
                onLike = actions?.let { { if (isLiked) it.onUnlike() else it.onLike() } },
                onEmojiReact = actions?.onReact,
                onEmojiUnreact = actions?.onUnreact,
                onOpenStandardEmojiPicker = { showStandardEmojiPicker = true },
            )
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (showStandardEmojiPicker && actions != null && !reactions.hasOwnReaction) {
        StandardEmojiPickerSheet(
            onDismiss = { showStandardEmojiPicker = false },
            onSelect = { option ->
                showStandardEmojiPicker = false
                when (option) {
                    is ReactionOption.Unicode -> CustomEmojiStore.markUnicodeUsed(option.value)
                    is ReactionOption.Custom -> CustomEmojiStore.markCustomReactionUsed(option.shortcode, option.imageUrl)
                }
                actions.onReact(option)
            },
        )
    }
}

@Composable
private fun ArticleCommentItem(
    event: NostrEvent,
    profile: NostrProfile?,
    onUserClick: (pubkey: String) -> Unit,
) {
    val imageUrls = remember(event.content) { extractImageUrls(event.content) }
    val textContent = remember(event.content) { stripImageUrls(stripNostrEventUris(event.content)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AvatarCircle(
            pubkey = event.pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 36,
            modifier = Modifier.clickable { onUserClick(event.pubkey) },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProfileNameText(
                    profile = profile,
                    fallback = event.shortPubkey,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable { onUserClick(event.pubkey) },
                )
                Text(
                    text = formatTimestamp(event.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (textContent.isNotBlank()) {
                LinkedText(
                    text = textContent,
                    style = MaterialTheme.typography.bodyMedium,
                    customEmojis = event.tags.customEmojiMap(),
                    onProfileClick = onUserClick,
                )
            }
            imageUrls.firstOrNull()?.let { imageUrl ->
                NetworkImage(
                    url = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop,
                    maxDecodeSizePx = 720,
                )
            }
        }
    }
}

@Composable
private fun EngagementMessage(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
