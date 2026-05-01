package com.nostr.torinos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.ui.profile.AvatarCircle
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable
fun NoteCard(
    event: NostrEvent,
    profile: NostrProfile?,
    repostedByPubkey: String? = null,
    repostedByProfile: NostrProfile? = null,
    replyCount: Int,
    reactionCount: Int,
    repostCount: Int = 0,
    isLiked: Boolean = false,
    isReposted: Boolean = false,
    onUserClick: (pubkey: String) -> Unit = {},
    onLike: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onOpenReplies: (() -> Unit)? = null,
    onOpenLikes: (() -> Unit)? = null,
    onOpenReposts: (() -> Unit)? = null,
    onRepost: (() -> Unit)? = null,
    quotedEvents: List<QuotedEvent> = emptyList(),
    ownPubkey: String? = null,
    onDelete: (() -> Unit)? = null,
    isMuted: Boolean = false,
    onMute: (() -> Unit)? = null,
    onUnmute: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    var expandedImageUrl by remember { mutableStateOf<String?>(null) }
    val isOwnPost = ownPubkey != null && event.pubkey == ownPubkey
    val hasMenu = (isOwnPost && onDelete != null) || (!isOwnPost && (onMute != null || onUnmute != null))

    expandedImageUrl?.let { url ->
        ExpandedImageDialog(
            url = url,
            onDismiss = { expandedImageUrl = null },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCircle(
            pubkey = event.pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            modifier = Modifier.clickable { onUserClick(event.pubkey) },
        )

        Column(modifier = Modifier.weight(1f)) {
            if (repostedByPubkey != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onUserClick(repostedByPubkey) },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${repostedByProfile?.bestName ?: shortPubkey(repostedByPubkey)} がリポスト",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profile?.bestName ?: event.shortPubkey,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onUserClick(event.pubkey) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTimestamp(event.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (hasMenu) {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "メニュー",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            if (isOwnPost && onDelete != null) {
                                DropdownMenuItem(
                                    text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showMenu = false
                                        onDelete()
                                    },
                                )
                            }
                            if (!isOwnPost) {
                                if (isMuted) {
                                    DropdownMenuItem(
                                        text = { Text("ミュートを解除") },
                                        onClick = {
                                            showMenu = false
                                            onUnmute?.invoke()
                                        },
                                    )
                                } else if (onMute != null) {
                                    DropdownMenuItem(
                                        text = { Text("ミュート") },
                                        onClick = {
                                            showMenu = false
                                            onMute()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            val imageUrls = extractImageUrls(event.content)
            val contentWithoutQuotes = stripNostrEventUris(event.content)
            val linkPreviewUrl = extractWebUrls(contentWithoutQuotes)
                .firstOrNull { !isImageUrl(it) }
            val textContent = if (imageUrls.isNotEmpty()) {
                stripImageUrls(contentWithoutQuotes)
            } else {
                contentWithoutQuotes
            }
            if (textContent.isNotBlank()) {
                LinkedText(
                    text = textContent,
                    style = MaterialTheme.typography.bodyMedium,
                    onProfileClick = onUserClick,
                )
            }
            if (imageUrls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                ImagePreviewGrid(
                    imageUrls = imageUrls,
                    onImageClick = { expandedImageUrl = it },
                )
            }
            linkPreviewUrl?.let { url ->
                Spacer(modifier = Modifier.height(8.dp))
                LinkPreviewCard(url = url)
            }
            quotedEvents.forEach { quote ->
                Spacer(modifier = Modifier.height(8.dp))
                QuotePreview(
                    event = quote.event,
                    profile = quote.profile,
                    onUserClick = onUserClick,
                    onImageClick = { expandedImageUrl = it },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EngagementCount(
                    icon = Icons.Default.MailOutline,
                    contentDescription = "返信",
                    count = replyCount,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onReply,
                    onCountClick = onOpenReplies,
                )
                EngagementCount(
                    icon = Icons.Default.Repeat,
                    contentDescription = "リポスト",
                    count = repostCount,
                    tint = if (isReposted) Color(0xFF2BAE66) else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onRepost,
                    onCountClick = onOpenReposts,
                )
                EngagementCount(
                    icon = Icons.Default.Favorite,
                    contentDescription = "いいね",
                    count = reactionCount,
                    tint = if (isLiked) Color(0xFFE17055) else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onLike,
                    onCountClick = onOpenLikes,
                )
            }
        }
    }
}

data class QuotedEvent(
    val event: NostrEvent,
    val profile: NostrProfile?,
)

@Composable
private fun QuotePreview(
    event: NostrEvent,
    profile: NostrProfile?,
    onUserClick: (pubkey: String) -> Unit,
    onImageClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.small,
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = profile?.bestName ?: event.shortPubkey,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onUserClick(event.pubkey) },
            )
            Text(
                text = formatTimestamp(event.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val imageUrls = extractImageUrls(event.content)
        val textContent = if (imageUrls.isNotEmpty()) {
            stripImageUrls(stripNostrEventUris(event.content))
        } else {
            stripNostrEventUris(event.content)
        }
        if (textContent.isNotBlank()) {
            LinkedText(
                text = textContent,
                style = MaterialTheme.typography.bodySmall,
                onProfileClick = onUserClick,
            )
        }
        if (imageUrls.isNotEmpty()) {
            ImagePreviewGrid(
                imageUrls = imageUrls,
                singleImageMaxHeight = 180.dp,
                onImageClick = onImageClick,
            )
        }
    }
}

@Composable
private fun ImagePreviewGrid(
    imageUrls: List<String>,
    singleImageMaxHeight: Dp = 400.dp,
    onImageClick: (String) -> Unit,
) {
    if (imageUrls.size == 1) {
        val url = imageUrls.first()
        NetworkImage(
            url = url,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = singleImageMaxHeight)
                .clip(MaterialTheme.shapes.small)
                .clickable { onImageClick(url) },
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.small),
    ) {
        when (imageUrls.size) {
            2 -> TwoImageGrid(imageUrls, onImageClick)
            3 -> ThreeImageGrid(imageUrls, onImageClick)
            else -> FourImageGrid(imageUrls, onImageClick)
        }
    }
}

@Composable
private fun TwoImageGrid(
    imageUrls: List<String>,
    onImageClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        imageUrls.take(2).forEach { url ->
            GridImage(
                url = url,
                modifier = Modifier.weight(1f),
                onClick = { onImageClick(url) },
            )
        }
    }
}

@Composable
private fun ThreeImageGrid(
    imageUrls: List<String>,
    onImageClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        GridImage(
            url = imageUrls[0],
            modifier = Modifier.weight(1f),
            onClick = { onImageClick(imageUrls[0]) },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            GridImage(
                url = imageUrls[1],
                modifier = Modifier.weight(1f),
                onClick = { onImageClick(imageUrls[1]) },
            )
            GridImage(
                url = imageUrls[2],
                modifier = Modifier.weight(1f),
                onClick = { onImageClick(imageUrls[2]) },
            )
        }
    }
}

@Composable
private fun FourImageGrid(
    imageUrls: List<String>,
    onImageClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        imageUrls.take(4).chunked(2).forEachIndexed { rowIndex, rowUrls ->
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rowUrls.forEachIndexed { index, url ->
                    val imageIndex = rowIndex * 2 + index
                    Box(modifier = Modifier.weight(1f)) {
                        GridImage(
                            url = url,
                            onClick = { onImageClick(url) },
                        )
                        if (imageIndex == 3 && imageUrls.size > 4) {
                            MoreImagesOverlay(extraCount = imageUrls.size - 4)
                        }
                    }
                    if (rowUrls.size == 1 && index == 0) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun GridImage(
    url: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    NetworkImage(
        url = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .clickable { onClick() },
    )
}

@Composable
private fun ExpandedImageDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            NetworkImage(
                url = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .clickable { },
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "閉じる",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun MoreImagesOverlay(extraCount: Int) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.45f)),
        )
        Text(
            text = "+$extraCount",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}


@Composable
fun EngagementCount(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    count: Int,
    tint: androidx.compose.ui.graphics.Color,
    onClick: (() -> Unit)? = null,
    onCountClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = if (onClick != null) {
                Modifier
                    .size(36.dp)
                    .clickable { onClick() }
            } else {
                Modifier.size(36.dp)
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = tint,
            )
        }
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(min = 20.dp)
                .clickable(enabled = onCountClick != null) { onCountClick?.invoke() },
        )
    }
}

fun formatTimestamp(epochSeconds: Long): String = try {
    val local = Instant.fromEpochSeconds(epochSeconds)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    buildString {
        append((local.month.ordinal + 1).toString().padStart(2, '0'))
        append('/')
        append(local.day.toString().padStart(2, '0'))
        append(' ')
        append(local.hour.toString().padStart(2, '0'))
        append(':')
        append(local.minute.toString().padStart(2, '0'))
    }
} catch (_: Exception) {
    ""
}

private fun shortPubkey(pubkey: String): String = pubkey.take(8) + "…" + pubkey.takeLast(8)
