package com.nostr.torinos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
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
import androidx.compose.ui.text.style.TextOverflow
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
    profiles: Map<String, NostrProfile> = emptyMap(),
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
    onHashtagClick: ((tag: String) -> Unit)? = null,
    quotedEvents: List<QuotedEvent> = emptyList(),
    replyParent: QuotedEvent? = null,
    ownPubkey: String? = null,
    onDelete: (() -> Unit)? = null,
    isMuted: Boolean = false,
    onMute: (() -> Unit)? = null,
    onUnmute: (() -> Unit)? = null,
    onNoteClick: ((eventId: String) -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    var expandedImageState by remember { mutableStateOf<ExpandedImageState?>(null) }
    val isOwnPost = ownPubkey != null && event.pubkey == ownPubkey
    val hasMenu = (isOwnPost && onDelete != null) || (!isOwnPost && (onMute != null || onUnmute != null))
    val parsedContent = remember(event.content) {
        parseNoteContent(event.content)
    }

    expandedImageState?.let { state ->
        ExpandedImageDialog(
            imageUrls = state.urls,
            initialIndex = state.initialIndex,
            onDismiss = { expandedImageState = null },
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onUserClick(event.pubkey) },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        text = event.clientName
                            ?.let { "${formatTimestamp(event.createdAt)} · $it" }
                            ?: formatTimestamp(event.createdAt),
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
            if (replyParent != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "返信先: ${replyParent.profile?.bestName ?: replyParent.event.shortPubkey}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onUserClick(replyParent.event.pubkey) },
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                QuotePreview(
                    event = replyParent.event,
                    profile = replyParent.profile,
                    profiles = profiles,
                    onUserClick = onUserClick,
                    onImageClick = { urls, index -> expandedImageState = ExpandedImageState(urls, index) },
                    onNoteClick = onNoteClick,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (parsedContent.textContent.isNotBlank()) {
                LinkedText(
                    text = parsedContent.textContent,
                    style = MaterialTheme.typography.bodyMedium,
                    onProfileClick = onUserClick,
                    profiles = profiles,
                    onHashtagClick = onHashtagClick,
                )
            }
            if (parsedContent.imageUrls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                ImagePreviewGrid(
                    imageUrls = parsedContent.imageUrls,
                    onImageClick = { urls, index -> expandedImageState = ExpandedImageState(urls, index) },
                )
            }
            parsedContent.linkPreviewUrl?.let { url ->
                LinkPreviewCard(url = url)
            }
            quotedEvents.forEach { quote ->
                Spacer(modifier = Modifier.height(8.dp))
                QuotePreview(
                    event = quote.event,
                    profile = quote.profile,
                    profiles = profiles,
                    onUserClick = onUserClick,
                    onImageClick = { urls, index -> expandedImageState = ExpandedImageState(urls, index) },
                    onNoteClick = onNoteClick,
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

private data class ExpandedImageState(
    val urls: List<String>,
    val initialIndex: Int,
)

private data class ParsedNoteContent(
    val textContent: String,
    val imageUrls: List<String>,
    val linkPreviewUrl: String?,
)

private fun parseNoteContent(content: String): ParsedNoteContent {
    val imageUrls = extractImageUrls(content)
    val contentWithoutQuotes = stripNostrEventUris(content)
    val linkPreviewUrl = extractWebUrls(contentWithoutQuotes)
        .firstOrNull { !isImageUrl(it) }
    val textContent = if (imageUrls.isNotEmpty()) {
        stripImageUrls(contentWithoutQuotes)
    } else {
        contentWithoutQuotes
    }
    return ParsedNoteContent(
        textContent = textContent,
        imageUrls = imageUrls,
        linkPreviewUrl = linkPreviewUrl,
    )
}

@Composable
private fun QuotePreview(
    event: NostrEvent,
    profile: NostrProfile?,
    profiles: Map<String, NostrProfile>,
    onUserClick: (pubkey: String) -> Unit,
    onImageClick: (List<String>, Int) -> Unit,
    onNoteClick: ((eventId: String) -> Unit)? = null,
) {
    val parsedContent = remember(event.content) {
        parseNoteContent(event.content)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.small,
            )
            .clip(MaterialTheme.shapes.small)
            .then(
                if (onNoteClick != null) Modifier.clickable { onNoteClick(event.id) }
                else Modifier
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onUserClick(event.pubkey) },
            )
            Text(
                text = formatTimestamp(event.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (parsedContent.textContent.isNotBlank()) {
            LinkedText(
                text = parsedContent.textContent,
                style = MaterialTheme.typography.bodySmall,
                onProfileClick = onUserClick,
                profiles = profiles,
                onHashtagClick = null,
            )
        }
        if (parsedContent.imageUrls.isNotEmpty()) {
            ImagePreviewGrid(
                imageUrls = parsedContent.imageUrls,
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
    onImageClick: (List<String>, Int) -> Unit,
) {
    if (imageUrls.size == 1) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val imageHeight = minOf(maxWidth * 9f / 16f, singleImageMaxHeight)
            NetworkImage(
                url = imageUrls.first(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(imageHeight)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onImageClick(imageUrls, 0) },
            )
        }
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
    onImageClick: (List<String>, Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        imageUrls.take(2).forEachIndexed { index, url ->
            GridImage(
                url = url,
                modifier = Modifier.weight(1f),
                onClick = { onImageClick(imageUrls, index) },
            )
        }
    }
}

@Composable
private fun ThreeImageGrid(
    imageUrls: List<String>,
    onImageClick: (List<String>, Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        GridImage(
            url = imageUrls[0],
            modifier = Modifier.weight(1f),
            onClick = { onImageClick(imageUrls, 0) },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            GridImage(
                url = imageUrls[1],
                modifier = Modifier.weight(1f),
                onClick = { onImageClick(imageUrls, 1) },
            )
            GridImage(
                url = imageUrls[2],
                modifier = Modifier.weight(1f),
                onClick = { onImageClick(imageUrls, 2) },
            )
        }
    }
}

@Composable
private fun FourImageGrid(
    imageUrls: List<String>,
    onImageClick: (List<String>, Int) -> Unit,
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
                            onClick = { onImageClick(imageUrls, imageIndex) },
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
    imageUrls: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { imageUrls.size },
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    NetworkImage(
                        url = imageUrls[page],
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                    )
                }
            }
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
            if (imageUrls.size > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    imageUrls.indices.forEach { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
                                ),
                        )
                    }
                }
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
