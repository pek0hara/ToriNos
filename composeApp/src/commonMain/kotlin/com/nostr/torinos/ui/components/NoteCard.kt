package com.nostr.torinos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.network.RecentReaction
import com.nostr.torinos.ui.profile.customEmojiMap
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.ui.settings.setPlainText
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Instant

val LocalQuotePostHandler = compositionLocalOf<((NostrEvent) -> Unit)?> { null }

@Composable
fun NoteCard(
    event: NostrEvent,
    profile: NostrProfile?,
    repostedByPubkey: String? = null,
    repostedByProfile: NostrProfile? = null,
    profiles: Map<String, NostrProfile> = emptyMap(),
    replyCount: Int,
    replies: List<NostrEvent> = emptyList(),
    reactionCount: Int,
    likeReactionCount: Int? = null,
    customReactions: List<CustomReaction> = emptyList(),
    unicodeReactions: List<UnicodeReaction> = emptyList(),
    repostCount: Int = 0,
    isLiked: Boolean = false,
    isReposted: Boolean = false,
    ownEmojiReactionEventIds: Map<String, String> = emptyMap(),
    onUserClick: (pubkey: String) -> Unit = {},
    onLike: (() -> Unit)? = null,
    onEmojiReact: ((ReactionOption) -> Unit)? = null,
    onEmojiUnreact: ((ReactionOption) -> Unit)? = null,
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
    onReport: ((reason: String, detail: String) -> Unit)? = null,
    onNoteClick: ((eventId: String) -> Unit)? = null,
) {
    val onQuote = LocalQuotePostHandler.current
    var showMenu by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showHeartReactionMenu by remember { mutableStateOf(false) }
    var showStandardEmojiPicker by remember { mutableStateOf(false) }
    var expandedImageState by remember { mutableStateOf<ExpandedImageState?>(null) }
    var repliesExpanded by remember(event.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val isOwnPost = ownPubkey != null && event.pubkey == ownPubkey
    val hasOwnReaction = isLiked || ownEmojiReactionEventIds.isNotEmpty()
    val canExpandReplies = replyCount > 0 && replies.isNotEmpty()
    val ownEmojiReaction = remember(
        customReactions,
        unicodeReactions,
        ownEmojiReactionEventIds,
    ) {
        customReactions
            .asSequence()
            .map { ReactionOption.Custom(it.shortcode, it.imageUrl) }
            .plus(unicodeReactions.asSequence().map { ReactionOption.Unicode(it.content) })
            .firstOrNull { ownEmojiReactionEventIds.containsKey(it.key) }
    }
    val onHeartClick: (() -> Unit)? = when {
        isLiked -> onLike
        ownEmojiReaction != null && onEmojiUnreact != null -> {
            { onEmojiUnreact(ownEmojiReaction) }
        }
        !hasOwnReaction -> onLike
        else -> null
    }
    val hasMenu = true
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

    if (showReportDialog && onReport != null) {
        ReportDialog(
            onDismiss = { showReportDialog = false },
            onReport = { reason, detail ->
                showReportDialog = false
                onReport(reason, detail)
            },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onNoteClick != null) Modifier.clickable { onNoteClick(event.id) }
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AvatarCircle(
            pubkey = event.pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 42,
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
                    LinkedText(
                        text = "${repostedByProfile?.bestName ?: shortPubkey(repostedByPubkey)} がリポスト",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        customEmojis = repostedByProfile?.customEmojis.orEmpty(),
                        enableWebLinks = false,
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
                ProfileNameText(
                    profile = profile,
                    fallback = event.shortPubkey,
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
                            ?.let { "${formatTimestamp(event.createdAt, todayTimeOnly = true)} · $it" }
                            ?: formatTimestamp(event.createdAt, todayTimeOnly = true),
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
                            DropdownMenuItem(
                                text = { Text("本文をコピー") },
                                onClick = {
                                    showMenu = false
                                    coroutineScope.launch {
                                        clipboard.setPlainText(event.content)
                                    }
                                },
                            )
                            if (onQuote != null) {
                                DropdownMenuItem(
                                    text = { Text("投稿を引用") },
                                    onClick = {
                                        showMenu = false
                                        onQuote(event)
                                    },
                                )
                            }
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
                                        text = { Text("ブロックを解除") },
                                        onClick = {
                                            showMenu = false
                                            onUnmute?.invoke()
                                        },
                                    )
                                } else if (onMute != null) {
                                    DropdownMenuItem(
                                        text = { Text("ユーザーをブロック") },
                                        onClick = {
                                            showMenu = false
                                            onMute()
                                        },
                                    )
                                }
                                if (onReport != null) {
                                    DropdownMenuItem(
                                        text = { Text("通報", color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showMenu = false
                                            showReportDialog = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
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
                Spacer(modifier = Modifier.height(2.dp))
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
                CollapsibleNoteText(
                    text = parsedContent.textContent,
                    style = MaterialTheme.typography.bodyMedium,
                    customEmojis = event.tags.customEmojiMap(),
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
            if (reactionCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                ReactionSummaryRow(
                    totalReactionCount = reactionCount,
                    explicitLikeCount = likeReactionCount,
                    isLiked = isLiked,
                    customReactions = customReactions,
                    unicodeReactions = unicodeReactions,
                    ownEmojiReactionEventIds = ownEmojiReactionEventIds,
                    onLike = onLike,
                    onEmojiReact = onEmojiReact,
                    onEmojiUnreact = onEmojiUnreact,
                    onOpenStandardEmojiPicker = { showStandardEmojiPicker = true },
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
                    countText = if (canExpandReplies) {
                        "$replyCount${if (repliesExpanded) "⌃" else "⌄"}"
                    } else {
                        replyCount.toString()
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onReply,
                    onCountClick = if (canExpandReplies) {
                        { repliesExpanded = !repliesExpanded }
                    } else {
                        onOpenReplies
                    },
                )
                EngagementCount(
                    icon = Icons.Default.Repeat,
                    contentDescription = "リポスト",
                    count = repostCount,
                    tint = if (isReposted) Color(0xFF2BAE66) else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onRepost,
                    onCountClick = onOpenReposts,
                )
                Box {
                    EngagementCount(
                        icon = Icons.Default.Favorite,
                        contentDescription = if (hasOwnReaction) "リアクションを解除" else "いいね",
                        count = reactionCount,
                        tint = if (hasOwnReaction) Color(0xFFE17055) else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onHeartClick,
                        onLongClick = if (onEmojiReact != null && !hasOwnReaction) {
                            { showHeartReactionMenu = true }
                        } else {
                            null
                        },
                        onCountClick = onOpenLikes,
                    )
                    QuickReactionMenu(
                        expanded = showHeartReactionMenu,
                        selectedReactionKeys = ownEmojiReactionEventIds.keys,
                        onDismiss = { showHeartReactionMenu = false },
                        onSelect = { option ->
                            showHeartReactionMenu = false
                            if (ownEmojiReactionEventIds.containsKey(option.key)) {
                                onEmojiUnreact?.invoke(option)
                            } else {
                                onEmojiReact?.invoke(option)
                            }
                        },
                        onOpenStandardEmojiPicker = {
                            showHeartReactionMenu = false
                            showStandardEmojiPicker = true
                        },
                    )
                }
            }
            if (repliesExpanded && replies.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    replies.forEach { reply ->
                        QuotePreview(
                            event = reply,
                            profile = profiles[reply.pubkey],
                            profiles = profiles,
                            onUserClick = onUserClick,
                            onImageClick = { urls, index ->
                                expandedImageState = ExpandedImageState(urls, index)
                            },
                            onNoteClick = onNoteClick,
                        )
                    }
                }
            }
        }
    }

    if (showStandardEmojiPicker && !hasOwnReaction) {
        StandardEmojiPickerSheet(
            onDismiss = { showStandardEmojiPicker = false },
            onSelect = { option ->
                showStandardEmojiPicker = false
                markReactionUsed(option)
                onEmojiReact?.invoke(option)
            },
        )
    }
}

@Composable
private fun ReactionSummaryRow(
    totalReactionCount: Int,
    explicitLikeCount: Int?,
    isLiked: Boolean,
    customReactions: List<CustomReaction>,
    unicodeReactions: List<UnicodeReaction>,
    ownEmojiReactionEventIds: Map<String, String>,
    onLike: (() -> Unit)?,
    onEmojiReact: ((ReactionOption) -> Unit)?,
    onEmojiUnreact: ((ReactionOption) -> Unit)?,
    onOpenStandardEmojiPicker: () -> Unit,
) {
    val reactionChipColors = reactionChipColors(isSystemInDarkTheme())
    val emojiReactionCount = customReactions.sumOf { it.count } + unicodeReactions.sumOf { it.count }
    val likeCount = explicitLikeCount
        ?: (totalReactionCount - emojiReactionCount).coerceAtLeast(if (isLiked) 1 else 0)
    val hasOwnReaction = isLiked || ownEmojiReactionEventIds.isNotEmpty()
    var showQuickMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (likeCount > 0) {
            ReactionChip(
                selected = isLiked,
                enabled = !hasOwnReaction || isLiked,
                contentDescription = "いいね、${likeCount}件",
                onClick = if (!hasOwnReaction || isLiked) onLike else null,
                emoji = {
                    Text(
                        text = "❤️",
                        modifier = Modifier.widthIn(min = 28.dp),
                        fontSize = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                    )
                },
                count = likeCount,
            )
        }
        customReactions.forEach { reaction ->
            val option = ReactionOption.Custom(reaction.shortcode, reaction.imageUrl)
            val selected = ownEmojiReactionEventIds.containsKey(option.key)
            ReactionChip(
                selected = selected,
                enabled = !hasOwnReaction || selected,
                contentDescription = ":${reaction.shortcode}:、${reaction.count}件",
                onClick = if (onEmojiReact != null && (!hasOwnReaction || selected)) {
                    {
                        if (selected) {
                            onEmojiUnreact?.invoke(option)
                        } else {
                            onEmojiReact(option)
                        }
                    }
                } else null,
                emoji = {
                    Box(
                    modifier = Modifier.size(width = 28.dp, height = 32.dp),
                    contentAlignment = Alignment.Center,
                    ) {
                        NetworkImage(
                            url = reaction.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                count = reaction.count,
            )
        }
        unicodeReactions.forEach { reaction ->
            val option = ReactionOption.Unicode(reaction.content)
            val selected = ownEmojiReactionEventIds.containsKey(option.key)
            ReactionChip(
                selected = selected,
                enabled = !hasOwnReaction || selected,
                contentDescription = "${reaction.content}、${reaction.count}件",
                onClick = if (onEmojiReact != null && (!hasOwnReaction || selected)) {
                    {
                        if (selected) {
                            onEmojiUnreact?.invoke(option)
                        } else {
                            onEmojiReact(option)
                        }
                    }
                } else null,
                emoji = {
                    Text(
                        text = reaction.content,
                        modifier = Modifier.widthIn(min = 28.dp),
                        fontSize = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                    )
                },
                count = reaction.count,
            )
        }
        if (onEmojiReact != null && !hasOwnReaction) {
            Box {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(reactionChipColors.background)
                        .border(1.dp, reactionChipColors.border, CircleShape)
                        .clickable { showQuickMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "リアクションを追加",
                        modifier = Modifier.size(18.dp),
                        tint = reactionChipColors.content,
                    )
                }
                QuickReactionMenu(
                    expanded = showQuickMenu,
                    selectedReactionKeys = ownEmojiReactionEventIds.keys,
                    onDismiss = { showQuickMenu = false },
                    onSelect = { option ->
                        showQuickMenu = false
                        if (ownEmojiReactionEventIds.containsKey(option.key)) {
                            onEmojiUnreact?.invoke(option)
                        } else {
                            onEmojiReact(option)
                        }
                    },
                    onOpenStandardEmojiPicker = {
                        showQuickMenu = false
                        onOpenStandardEmojiPicker()
                    },
                )
            }
        }
    }
}

@Composable
private fun ReactionChip(
    selected: Boolean,
    enabled: Boolean = true,
    contentDescription: String,
    onClick: (() -> Unit)?,
    emoji: @Composable () -> Unit,
    count: Int,
) {
    val colors = reactionChipColors(isSystemInDarkTheme())
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.55f)
            .clip(shape)
            .background(
                if (selected) {
                    colors.selectedBackground
                } else {
                    colors.background
                },
            )
            .border(
                width = 1.dp,
                color = if (selected) {
                    colors.selectedBorder
                } else {
                    colors.border
                },
                shape = shape,
            )
            .height(32.dp)
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() }
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        emoji()
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                colors.selectedContent
            } else {
                colors.content
            },
        )
    }
}

private data class ReactionChipColors(
    val background: Color,
    val content: Color,
    val border: Color,
    val selectedBackground: Color,
    val selectedContent: Color,
    val selectedBorder: Color,
)

private fun reactionChipColors(darkTheme: Boolean): ReactionChipColors =
    if (darkTheme) {
        ReactionChipColors(
            background = Color(0xFF353A40),
            content = Color(0xFFD7DCE2),
            border = Color(0xFF50565E),
            selectedBackground = Color(0xFF34495E),
            selectedContent = Color(0xFFD6EAFF),
            selectedBorder = Color(0xFF6887A6),
        )
    } else {
        ReactionChipColors(
            background = Color(0xFFF2F4F7),
            content = Color(0xFF5F6670),
            border = Color(0xFFD7DCE2),
            selectedBackground = Color(0xFFD6EAFF),
            selectedContent = Color(0xFF003A80),
            selectedBorder = Color(0xFF2292FF),
        )
    }

@Composable
private fun QuickReactionMenu(
    expanded: Boolean,
    selectedReactionKeys: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (ReactionOption) -> Unit,
    onOpenStandardEmojiPicker: () -> Unit,
) {
    val savedCustomEmojis by CustomEmojiStore.emojis.collectAsState()
    val recentReactions by CustomEmojiStore.recentReactions.collectAsState()
    val savedEmojiMap = remember(savedCustomEmojis) { savedCustomEmojis.associateBy { it.shortcode } }
    val recentOptions = remember(recentReactions, savedEmojiMap) {
        recentReactions.mapNotNull { recent ->
            when (recent.kind) {
                RecentReaction.UnicodeKind -> ReactionOption.Unicode(recent.value)
                RecentReaction.CustomKind -> savedEmojiMap[recent.value]?.let {
                    ReactionOption.Custom(it.shortcode, it.imageUrl)
                }
                else -> null
            }
        }.distinctBy { it.key }.take(8)
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 320.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "最近使ったリアクション",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (recentOptions.isEmpty()) {
                Text(
                    text = "まだありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    recentOptions.forEach { option ->
                        ReactionPickerTile(
                            option = option,
                            selected = option.key in selectedReactionKeys,
                            onClick = {
                                markReactionUsed(option)
                                onSelect(option)
                            },
                        )
                    }
                }
            }

            TextButton(
                onClick = onOpenStandardEmojiPicker,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("絵文字を選ぶ")
            }
        }
    }
}

@Composable
private fun ReactionPickerTile(
    option: ReactionOption?,
    selected: Boolean = false,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (option) {
            null -> Text(
                text = "❤️",
                fontSize = 22.sp,
                modifier = Modifier.semantics {
                    this.contentDescription = contentDescription ?: "いいね"
                },
            )
            is ReactionOption.Unicode -> Text(
                text = option.value,
                fontSize = 22.sp,
                modifier = Modifier.semantics {
                    this.contentDescription = option.value
                },
            )
            is ReactionOption.Custom -> NetworkImage(
                url = option.imageUrl,
                contentDescription = ":${option.shortcode}:",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

private fun markReactionUsed(option: ReactionOption) {
    when (option) {
        is ReactionOption.Unicode -> CustomEmojiStore.markUnicodeUsed(option.value)
        is ReactionOption.Custom -> CustomEmojiStore.markCustomReactionUsed(option.shortcode)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StandardEmojiPickerSheet(
    onDismiss: () -> Unit,
    onSelect: (ReactionOption) -> Unit,
) {
    val savedCustomEmojis by CustomEmojiStore.emojis.collectAsState()
    val recentReactions by CustomEmojiStore.recentReactions.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<StandardEmojiCategory?>(null) }
    var customOnly by remember { mutableStateOf(false) }
    val normalizedQuery = query.trim().lowercase()
    val customOptions = remember(savedCustomEmojis) {
        savedCustomEmojis.map { ReactionOption.Custom(it.shortcode, it.imageUrl) }
    }
    val recentOptions = remember(recentReactions, savedCustomEmojis) {
        val customEmojiMap = savedCustomEmojis.associateBy { it.shortcode }
        recentReactions
            .asSequence()
            .mapNotNull { recent ->
                when (recent.kind) {
                    RecentReaction.UnicodeKind -> ReactionOption.Unicode(recent.value)
                    RecentReaction.CustomKind -> customEmojiMap[recent.value]?.let {
                        ReactionOption.Custom(it.shortcode, it.imageUrl)
                    }
                    else -> null
                }
            }
            .distinctBy { it.key }
            .take(16)
            .toList()
    }
    val visibleSections = remember(normalizedQuery, selectedCategory, customOnly, customOptions) {
        when {
            normalizedQuery.isNotBlank() -> {
                val unicodeMatches = STANDARD_EMOJI_CATEGORIES.flatMap { category ->
                    category.emojis.filter { emoji ->
                        normalizedQuery in emoji ||
                            normalizedQuery in category.label.lowercase() ||
                            EMOJI_SEARCH_KEYWORDS[emoji].orEmpty().any { normalizedQuery in it }
                    }
                }.distinct().map { ReactionOption.Unicode(it) }
                val customMatches = customOptions.filter {
                    normalizedQuery in it.shortcode.lowercase()
                }
                listOf(EmojiPickerSection("検索結果", customMatches + unicodeMatches))
            }
            customOnly -> listOf(EmojiPickerSection("カスタム絵文字", customOptions))
            selectedCategory != null -> listOf(
                EmojiPickerSection(
                    selectedCategory!!.label,
                    selectedCategory!!.emojis.map { ReactionOption.Unicode(it) },
                ),
            )
            else -> if (customOptions.isNotEmpty()) {
                listOf(EmojiPickerSection("カスタム絵文字", customOptions))
            } else {
                emptyList()
            } + STANDARD_EMOJI_CATEGORIES.map { category ->
                EmojiPickerSection(
                    category.label,
                    category.emojis.map { ReactionOption.Unicode(it) },
                )
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("検索") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "検索文字を消去",
                            )
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 42.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (
                    normalizedQuery.isBlank() &&
                    selectedCategory == null &&
                    !customOnly &&
                    recentOptions.isNotEmpty()
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmojiPickerSectionTitle("よく使う項目")
                    }
                    items(recentOptions, key = { "recent-${it.key}" }) { option ->
                        EmojiPickerGridTile(option = option, onSelect = onSelect)
                    }
                }

                visibleSections.forEach { section ->
                    item(
                        key = "title-${section.title}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        EmojiPickerSectionTitle(section.title)
                    }
                    items(
                        items = section.options,
                        key = { "${section.title}-${it.key}" },
                    ) { option ->
                        EmojiPickerGridTile(option = option, onSelect = onSelect)
                    }
                }

                if (visibleSections.all { it.options.isEmpty() }) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "一致する絵文字はありません",
                            modifier = Modifier.padding(vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EmojiCategoryButton(
                    icon = "",
                    iconVector = Icons.Default.History,
                    label = "すべてとよく使う項目",
                    selected = selectedCategory == null && !customOnly,
                    onClick = {
                        query = ""
                        selectedCategory = null
                        customOnly = false
                    },
                )
                EmojiCategoryButton(
                    icon = "✦",
                    customImageUrl = savedCustomEmojis.firstOrNull()?.imageUrl,
                    label = "カスタム絵文字",
                    selected = customOnly,
                    onClick = {
                        query = ""
                        selectedCategory = null
                        customOnly = true
                    },
                )
                STANDARD_EMOJI_CATEGORIES.forEach { category ->
                    EmojiCategoryButton(
                        icon = category.icon,
                        label = category.label,
                        selected = selectedCategory == category,
                        onClick = {
                            query = ""
                            selectedCategory = category
                            customOnly = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmojiPickerSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmojiPickerGridTile(
    option: ReactionOption,
    onSelect: (ReactionOption) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onSelect(option) }
            .semantics {
                contentDescription = when (option) {
                    is ReactionOption.Unicode -> option.value
                    is ReactionOption.Custom -> ":${option.shortcode}:"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (option) {
            is ReactionOption.Unicode -> Text(text = option.value, fontSize = 27.sp)
            is ReactionOption.Custom -> NetworkImage(
                url = option.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun EmojiCategoryButton(
    icon: String,
    iconVector: ImageVector? = null,
    customImageUrl: String? = null,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (iconVector != null) {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (customImageUrl != null) {
            NetworkImage(
                url = customImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Text(
                text = icon,
                fontSize = 21.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AllReactionPickerDialog(
    isLiked: Boolean,
    selectedReactionKeys: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (ReactionOption) -> Unit,
    onSelectLike: () -> Unit,
) {
    val savedCustomEmojis by CustomEmojiStore.emojis.collectAsState()
    val recentReactions by CustomEmojiStore.recentReactions.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ReactionSearchFilter.All) }
    val normalizedQuery = remember(query) {
        query.trim().trim(':').lowercase()
    }
    val unicodeEntries = remember(normalizedQuery, filter) {
        if (filter == ReactionSearchFilter.Custom) return@remember emptyList()
        UNICODE_REACTION_CATALOG
            .filter { entry ->
                normalizedQuery.isBlank() ||
                    normalizedQuery in entry.value.lowercase() ||
                    entry.keywords.any { normalizedQuery in it }
            }
    }
    val customOptions = remember(savedCustomEmojis, normalizedQuery, filter) {
        if (filter == ReactionSearchFilter.Unicode) return@remember emptyList()
        savedCustomEmojis
            .filter {
                normalizedQuery.isBlank() ||
                    normalizedQuery in it.shortcode.lowercase()
            }
            .map { ReactionOption.Custom(it.shortcode, it.imageUrl) }
    }
    val recentOptions = remember(savedCustomEmojis, recentReactions, filter) {
        val savedEmojiMap = savedCustomEmojis.associateBy { it.shortcode }
        recentReactions
            .mapNotNull { recent ->
                when (recent.kind) {
                    RecentReaction.UnicodeKind -> ReactionOption.Unicode(recent.value)
                        .takeUnless { filter == ReactionSearchFilter.Custom }
                    RecentReaction.CustomKind -> savedEmojiMap[recent.value]?.let {
                        ReactionOption.Custom(it.shortcode, it.imageUrl)
                    }?.takeUnless { filter == ReactionSearchFilter.Unicode }
                    else -> null
                }
            }
            .take(8)
    }
    val resultCount = unicodeEntries.size + customOptions.size +
        if (
            filter != ReactionSearchFilter.Custom &&
            (normalizedQuery.isBlank() || "いいね".contains(normalizedQuery))
        ) 1 else 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("リアクションを探す") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("名前や :shortcode: で検索") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "検索文字を消去",
                                )
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReactionSearchFilter.entries.forEach { item ->
                        FilterChip(
                            selected = filter == item,
                            onClick = { filter = item },
                            label = { Text(item.label) },
                        )
                    }
                }

                if (normalizedQuery.isBlank() && recentOptions.isNotEmpty()) {
                    Text(
                        text = "最近使ったリアクション",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        lazyRowItems(
                            items = recentOptions,
                            key = { "recent-${it.key}" },
                        ) { option ->
                            ReactionSearchResultTile(
                                option = option,
                                selected = option.key in selectedReactionKeys,
                                label = when (option) {
                                    is ReactionOption.Unicode -> option.value
                                    is ReactionOption.Custom -> option.shortcode
                                },
                                onClick = {
                                    markReactionUsed(option)
                                    onSelect(option)
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (normalizedQuery.isBlank()) "すべての候補" else "検索結果",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${resultCount}件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 68.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 330.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (
                        filter != ReactionSearchFilter.Custom &&
                        (normalizedQuery.isBlank() || "いいね".contains(normalizedQuery))
                    ) {
                        item(key = "default-like") {
                            ReactionSearchResultTile(
                                option = null,
                                selected = isLiked,
                                label = "いいね",
                                onClick = onSelectLike,
                            )
                        }
                    }
                    items(
                        items = unicodeEntries,
                        key = { "unicode-${it.value}" },
                    ) { entry ->
                        val option = ReactionOption.Unicode(entry.value)
                        ReactionSearchResultTile(
                            option = option,
                            selected = option.key in selectedReactionKeys,
                            label = entry.keywords.firstOrNull() ?: entry.value,
                            onClick = {
                                markReactionUsed(option)
                                onSelect(option)
                            },
                        )
                    }
                    items(
                        items = customOptions,
                        key = { it.key },
                    ) { option ->
                        ReactionSearchResultTile(
                            option = option,
                            selected = option.key in selectedReactionKeys,
                            label = option.shortcode,
                            onClick = {
                                markReactionUsed(option)
                                onSelect(option)
                            },
                        )
                    }
                }
                if (resultCount == 0) {
                    Text(
                        text = if (normalizedQuery.isBlank()) {
                            "この種類のリアクションはまだありません"
                        } else {
                            "「${query.trim()}」に一致するリアクションはありません\n名前や :shortcode: を変えてお試しください"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

@Composable
private fun ReactionSearchResultTile(
    option: ReactionOption?,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 7.dp)
            .semantics {
                contentDescription = when (option) {
                    null -> "いいね"
                    is ReactionOption.Unicode -> label
                    is ReactionOption.Custom -> ":${option.shortcode}:"
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when (option) {
            null -> Text(text = "❤️", fontSize = 24.sp)
            is ReactionOption.Unicode -> Text(text = option.value, fontSize = 24.sp)
            is ReactionOption.Custom -> NetworkImage(
                url = option.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            text = if (option is ReactionOption.Custom) ":$label:" else label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class ReactionSearchFilter(val label: String) {
    All("すべて"),
    Unicode("標準絵文字"),
    Custom("カスタム"),
}

private data class UnicodeReactionEntry(
    val value: String,
    val keywords: List<String>,
)

private data class EmojiPickerSection(
    val title: String,
    val options: List<ReactionOption>,
)

private val EMOJI_SEARCH_KEYWORDS by lazy {
    UNICODE_REACTION_CATALOG.associate { it.value to it.keywords }
}

private val UNICODE_REACTION_CATALOG = listOf(
    UnicodeReactionEntry("😀", listOf("笑顔", "うれしい")),
    UnicodeReactionEntry("😃", listOf("笑顔", "うれしい")),
    UnicodeReactionEntry("👍", listOf("いいね", "賛成", "good")),
    UnicodeReactionEntry("👎", listOf("よくない", "反対", "bad")),
    UnicodeReactionEntry("😂", listOf("笑う", "爆笑", "笑顔")),
    UnicodeReactionEntry("🤣", listOf("笑う", "爆笑")),
    UnicodeReactionEntry("😊", listOf("笑顔", "うれしい")),
    UnicodeReactionEntry("🥰", listOf("好き", "笑顔")),
    UnicodeReactionEntry("😍", listOf("好き", "ハート")),
    UnicodeReactionEntry("🤔", listOf("考える", "疑問")),
    UnicodeReactionEntry("😮", listOf("驚く", "びっくり")),
    UnicodeReactionEntry("😢", listOf("悲しい", "泣く")),
    UnicodeReactionEntry("😭", listOf("悲しい", "泣く")),
    UnicodeReactionEntry("😡", listOf("怒る")),
    UnicodeReactionEntry("🥳", listOf("お祝い", "パーティー")),
    UnicodeReactionEntry("🤩", listOf("すごい", "感動")),
    UnicodeReactionEntry("🫡", listOf("了解", "敬礼")),
    UnicodeReactionEntry("🫠", listOf("溶ける")),
    UnicodeReactionEntry("🙌", listOf("万歳", "お祝い")),
    UnicodeReactionEntry("👏", listOf("拍手", "すごい")),
    UnicodeReactionEntry("🙏", listOf("お願い", "ありがとう", "感謝")),
    UnicodeReactionEntry("💪", listOf("がんばれ", "力")),
    UnicodeReactionEntry("🤝", listOf("握手", "同意")),
    UnicodeReactionEntry("👌", listOf("了解", "ok")),
    UnicodeReactionEntry("✌️", listOf("平和", "ピース")),
    UnicodeReactionEntry("👀", listOf("見る", "注目")),
    UnicodeReactionEntry("🎉", listOf("お祝い", "おめでとう")),
    UnicodeReactionEntry("✨", listOf("きらきら", "素敵")),
    UnicodeReactionEntry("🔥", listOf("炎", "熱い", "最高")),
    UnicodeReactionEntry("💯", listOf("満点", "最高")),
    UnicodeReactionEntry("💡", listOf("アイデア", "ひらめき")),
    UnicodeReactionEntry("✅", listOf("完了", "確認")),
    UnicodeReactionEntry("🚀", listOf("ロケット", "開始")),
    UnicodeReactionEntry("🐦", listOf("鳥", "とり")),
    UnicodeReactionEntry("🐣", listOf("ひよこ", "鳥")),
    UnicodeReactionEntry("🍣", listOf("寿司", "食べ物")),
    UnicodeReactionEntry("🍺", listOf("ビール", "乾杯")),
    UnicodeReactionEntry("☕", listOf("コーヒー", "休憩")),
)

data class QuotedEvent(
    val event: NostrEvent,
    val profile: NostrProfile?,
)

private enum class ReportReason(val label: String, val nip56Value: String) {
    Spam("スパム", "spam"),
    Impersonation("なりすまし", "impersonation"),
    Illegal("違法な内容", "illegal"),
    Malware("マルウェア", "malware"),
    Nudity("露骨な画像", "nudity"),
    Profanity("攻撃的な内容", "profanity"),
    Other("その他", "other"),
}

@Composable
private fun ReportDialog(
    onDismiss: () -> Unit,
    onReport: (reason: String, detail: String) -> Unit,
) {
    var selectedReason by remember { mutableStateOf(ReportReason.Spam) }
    var detail by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("投稿を通報") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportReason.entries.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedReason = reason }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedReason == reason,
                            onClick = { selectedReason = reason },
                        )
                        Text(
                            text = reason.label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it.take(500) },
                    label = { Text("補足（任意・通報後このユーザーをブロックします）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onReport(selectedReason.nip56Value, detail.trim()) },
            ) {
                Text("通報")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarCircle(
                pubkey = event.pubkey,
                name = profile?.bestName,
                pictureUrl = profile?.picture,
                size = 24,
                modifier = Modifier
                    .clickable { onUserClick(event.pubkey) },
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileNameText(
                    profile = profile,
                    fallback = event.shortPubkey,
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
                    text = formatTimestamp(event.createdAt, todayTimeOnly = true),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (parsedContent.textContent.isNotBlank()) {
            CollapsibleNoteText(
                text = parsedContent.textContent,
                style = MaterialTheme.typography.bodySmall,
                customEmojis = event.tags.customEmojiMap(),
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
private fun CollapsibleNoteText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    customEmojis: Map<String, String> = emptyMap(),
    onProfileClick: (pubkey: String) -> Unit,
    profiles: Map<String, NostrProfile>,
    onHashtagClick: ((tag: String) -> Unit)?,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    var hasHiddenLines by remember(text) { mutableStateOf(false) }
    val savedCustomEmojis by CustomEmojiStore.emojis.collectAsState()
    val customEmojiShortcodes = remember(savedCustomEmojis, customEmojis) {
        buildSet {
            addAll(customEmojis.keys)
            savedCustomEmojis.forEach { emoji -> add(emoji.shortcode) }
        }
    }
    val collapsedTextLength = remember(text, customEmojiShortcodes) {
        countTextWithCustomEmojis(text, customEmojiShortcodes)
    }
    val exceedsCharacterLimit = collapsedTextLength > CollapsedTextCharacterLimit
    val displayedText = if (!expanded && exceedsCharacterLimit) {
        truncateTextPreservingWebUrlsAndCustomEmojis(
            text = text,
            maxLength = CollapsedTextCharacterLimit,
            customEmojiShortcodes = customEmojiShortcodes,
        )
    } else {
        text
    }
    val shouldShowToggle = expanded || exceedsCharacterLimit || hasHiddenLines

    LinkedText(
        text = displayedText,
        style = style,
        customEmojis = customEmojis,
        onProfileClick = onProfileClick,
        profiles = profiles,
        onHashtagClick = onHashtagClick,
        maxLines = if (expanded) Int.MAX_VALUE else CollapsedTextMaxVisibleLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (!expanded) {
                hasHiddenLines = result.hasVisualOverflow
            }
        },
    )
    if (shouldShowToggle) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "閉じる" else "もっと見る")
        }
    }
}

private const val CollapsedTextCharacterLimit = 140
private const val CollapsedTextMaxVisibleLines = 9
private const val TimelineImageMaxDecodeSizePx = 720

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
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                maxDecodeSizePx = TimelineImageMaxDecodeSizePx,
                filterQuality = FilterQuality.Low,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(imageHeight)
                    .clickable { onImageClick(imageUrls, 0) },
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
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
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart,
        maxDecodeSizePx = TimelineImageMaxDecodeSizePx,
        filterQuality = FilterQuality.Low,
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
    var zoomedPage by remember { mutableStateOf<Int?>(null) }
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
                userScrollEnabled = zoomedPage == null,
            ) { page ->
                var scale by remember(imageUrls[page]) { mutableStateOf(1f) }
                var offset by remember(imageUrls[page]) { mutableStateOf(Offset.Zero) }
                val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
                    val wasZoomed = scale > 1f
                    val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
                    scale = nextScale
                    zoomedPage = if (nextScale > 1f) page else null
                    offset = if (nextScale == 1f) {
                        Offset.Zero
                    } else if (!wasZoomed) {
                        offset
                    } else {
                        val maxOffset = 2400f * max(1f, nextScale - 1f)
                        Offset(
                            x = (offset.x + panChange.x).coerceIn(-maxOffset, maxOffset),
                            y = (offset.y + panChange.y).coerceIn(-maxOffset, maxOffset),
                        )
                    }
                }
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
                        filterQuality = FilterQuality.High,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .transformable(
                                state = transformableState,
                                canPan = { scale > 1f },
                            )
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
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
    countText: String = count.toString(),
    tint: androidx.compose.ui.graphics.Color,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
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
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
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
            text = countText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(min = 20.dp)
                .clickable(enabled = onCountClick != null) { onCountClick?.invoke() },
        )
    }
}

fun formatTimestamp(
    epochSeconds: Long,
    todayTimeOnly: Boolean = false,
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String = try {
    val local = Instant.fromEpochSeconds(epochSeconds)
        .toLocalDateTime(timeZone)
    val timeOnly = if (todayTimeOnly) {
        val today = Instant.fromEpochSeconds(nowEpochSeconds)
            .toLocalDateTime(timeZone)
            .date
        if (local.date == today) {
            local.hour.toString().padStart(2, '0') +
                ":" +
                local.minute.toString().padStart(2, '0')
        } else {
            null
        }
    } else {
        null
    }
    timeOnly ?: buildString {
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
