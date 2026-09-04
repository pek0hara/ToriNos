package com.nostr.torinos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toReactionOption
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
    reactionEvents: List<NostrEvent> = emptyList(),
    repostCount: Int = 0,
    repostPubkeys: List<String> = emptyList(),
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
    onRefreshReactions: (() -> Unit)? = null,
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
    onQuotedNoteClick: ((eventId: String) -> Unit)? = onNoteClick,
    onReplyParentClick: ((eventId: String) -> Unit)? = onNoteClick,
) {
    val onQuote = LocalQuotePostHandler.current
    var showMenu by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showHeartReactionMenu by remember { mutableStateOf(false) }
    var showStandardEmojiPicker by remember { mutableStateOf(false) }
    var expandedImageState by remember { mutableStateOf<ExpandedImageState?>(null) }
    var expandedEngagement by remember(event.id) { mutableStateOf<ExpandedEngagement?>(null) }
    var sensitiveContentRevealed by remember(event.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val isOwnPost = ownPubkey != null && event.pubkey == ownPubkey
    val hasOwnReaction = isLiked || ownEmojiReactionEventIds.isNotEmpty()
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
    val contentWarningPresent = remember(event.tags) {
        hasContentWarning(event.tags)
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
                            .then(
                                if (onReplyParentClick != null) {
                                    Modifier.clickable { onReplyParentClick(replyParent.event.id) }
                                } else {
                                    Modifier
                                }
                            ),
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                QuotePreview(
                    event = replyParent.event,
                    profile = replyParent.profile,
                    profiles = profiles,
                    onImageClick = { urls, index -> expandedImageState = ExpandedImageState(urls, index) },
                    onNoteClick = onReplyParentClick,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (contentWarningPresent && !sensitiveContentRevealed) {
                SensitiveContentWarning(
                    onReveal = { sensitiveContentRevealed = true },
                )
            } else {
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
                        onImageClick = { urls, index -> expandedImageState = ExpandedImageState(urls, index) },
                        onNoteClick = onQuotedNoteClick,
                    )
                }
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
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                modifier = Modifier.offset(x = (-1).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EngagementCount(
                    icon = Icons.Default.MailOutline,
                    contentDescription = "返信",
                    count = replyCount,
                    countText = if (replyCount > 0) {
                        "$replyCount${if (expandedEngagement == ExpandedEngagement.Replies) "⌃" else "⌄"}"
                    } else {
                        replyCount.toString()
                    },
                    tint = if (expandedEngagement == ExpandedEngagement.Replies) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    onClick = onReply,
                    onCountClick = if (replyCount > 0) {
                        {
                            expandedEngagement = expandedEngagement.toggled(ExpandedEngagement.Replies)
                        }
                    } else {
                        onOpenReplies
                    },
                )
                EngagementCount(
                    icon = Icons.Default.Repeat,
                    contentDescription = "リポスト",
                    count = repostCount,
                    countText = if (repostCount > 0) {
                        "$repostCount${if (expandedEngagement == ExpandedEngagement.Reposts) "⌃" else "⌄"}"
                    } else {
                        repostCount.toString()
                    },
                    tint = when {
                        expandedEngagement == ExpandedEngagement.Reposts -> MaterialTheme.colorScheme.primary
                        isReposted -> Color(0xFF2BAE66)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    onClick = onRepost,
                    onCountClick = if (repostCount > 0) {
                        {
                            expandedEngagement = expandedEngagement.toggled(ExpandedEngagement.Reposts)
                        }
                    } else {
                        onOpenReposts
                    },
                )
                Box {
                    EngagementCount(
                        icon = Icons.Default.Favorite,
                        contentDescription = if (hasOwnReaction) "リアクションを解除" else "いいね",
                        count = reactionCount,
                        countText = if (reactionCount > 0) {
                            "$reactionCount${if (expandedEngagement == ExpandedEngagement.Reactions) "⌃" else "⌄"}"
                        } else {
                            reactionCount.toString()
                        },
                        tint = when {
                            expandedEngagement == ExpandedEngagement.Reactions -> MaterialTheme.colorScheme.primary
                            hasOwnReaction -> Color(0xFFE17055)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        onClick = onHeartClick,
                        onLongClick = if (onEmojiReact != null && !hasOwnReaction) {
                            { showHeartReactionMenu = true }
                        } else {
                            null
                        },
                        onCountClick = if (reactionCount > 0) {
                            {
                                if (expandedEngagement != ExpandedEngagement.Reactions) {
                                    onRefreshReactions?.invoke()
                                }
                                expandedEngagement = expandedEngagement.toggled(ExpandedEngagement.Reactions)
                            }
                        } else {
                            onOpenLikes
                        },
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
            AnimatedVisibility(
                visible = expandedEngagement != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                EngagementDetailsPanel(
                    expanded = expandedEngagement,
                    replyCount = replyCount,
                    replies = replies,
                    reactionCount = reactionCount,
                    likeReactionCount = likeReactionCount,
                    customReactions = customReactions,
                    unicodeReactions = unicodeReactions,
                    reactionEvents = reactionEvents,
                    repostCount = repostCount,
                    repostPubkeys = repostPubkeys,
                    profiles = profiles,
                    onUserClick = onUserClick,
                    onOpenReplies = onOpenReplies,
                    onOpenLikes = onOpenLikes,
                    onOpenReposts = onOpenReposts,
                    onOpenNote = onNoteClick,
                    onImageClick = { urls, index ->
                        expandedImageState = ExpandedImageState(urls, index)
                    },
                )
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

private enum class ExpandedEngagement {
    Replies,
    Reposts,
    Reactions,
}

private fun ExpandedEngagement?.toggled(target: ExpandedEngagement): ExpandedEngagement? =
    if (this == target) null else target

@Composable
private fun EngagementDetailsPanel(
    expanded: ExpandedEngagement?,
    replyCount: Int,
    replies: List<NostrEvent>,
    reactionCount: Int,
    likeReactionCount: Int?,
    customReactions: List<CustomReaction>,
    unicodeReactions: List<UnicodeReaction>,
    reactionEvents: List<NostrEvent>,
    repostCount: Int,
    repostPubkeys: List<String>,
    profiles: Map<String, NostrProfile>,
    onUserClick: (String) -> Unit,
    onOpenReplies: (() -> Unit)?,
    onOpenLikes: (() -> Unit)?,
    onOpenReposts: (() -> Unit)?,
    onOpenNote: ((String) -> Unit)?,
    onImageClick: (List<String>, Int) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (expanded) {
            ExpandedEngagement.Replies -> {
                replies.take(3).forEach { reply ->
                    QuotePreview(
                        event = reply,
                        profile = profiles[reply.pubkey],
                        profiles = profiles,
                        onImageClick = onImageClick,
                        onNoteClick = onOpenNote,
                    )
                }
                if (replies.isEmpty()) {
                    EngagementPlaceholder("返信を読み込むには「すべて見る」をタップ")
                }
                EngagementFooter(
                    visible = onOpenReplies != null && (replyCount > replies.take(3).size || replies.isEmpty()),
                    label = "返信をすべて見る ($replyCount)",
                    onClick = onOpenReplies,
                )
            }
            ExpandedEngagement.Reposts -> {
                EngagementUserGroup(
                    emoji = { Text("🔁", fontSize = 18.sp) },
                    count = repostCount,
                    pubkeys = repostPubkeys,
                    profiles = profiles,
                    onUserClick = onUserClick,
                )
                EngagementFooter(
                    visible = onOpenReposts != null &&
                        !engagementUsersAreFullyShown(repostCount, repostPubkeys),
                    label = "リポストをすべて見る ($repostCount)",
                    onClick = onOpenReposts,
                )
            }
            ExpandedEngagement.Reactions -> {
                val eventsByKey = reactionEvents.groupBy { reactionGroupKey(it) }
                val emojiCount = customReactions.sumOf { it.count } + unicodeReactions.sumOf { it.count }
                val resolvedLikeCount = likeReactionCount
                    ?: (reactionCount - emojiCount).coerceAtLeast(0)
                val groups = buildList {
                    if (resolvedLikeCount > 0) {
                        add(
                            ReactionPreviewGroup(
                                emoji = "❤️",
                                count = resolvedLikeCount,
                                pubkeys = eventsByKey[LikeReactionGroupKey].orEmpty().map { it.pubkey },
                            ),
                        )
                    }
                    customReactions.forEach { reaction ->
                        val key = ReactionOption.Custom(reaction.shortcode, reaction.imageUrl).key
                        val sourceReaction = eventsByKey[key]
                            .orEmpty()
                            .asSequence()
                            .mapNotNull { it.toCustomReaction() }
                            .firstOrNull()
                            ?: reaction
                        add(
                            ReactionPreviewGroup(
                                imageUrl = sourceReaction.imageUrl,
                                shortcode = sourceReaction.shortcode,
                                count = reaction.count,
                                pubkeys = eventsByKey[key].orEmpty().map { it.pubkey },
                            ),
                        )
                    }
                    unicodeReactions.forEach { reaction ->
                        val key = ReactionOption.Unicode(reaction.content).key
                        add(
                            ReactionPreviewGroup(
                                emoji = reaction.content,
                                count = reaction.count,
                                pubkeys = eventsByKey[key].orEmpty().map { it.pubkey },
                            ),
                        )
                    }
                }
                val allReactionsShown = resolvedLikeCount + emojiCount == reactionCount &&
                    groups.all { engagementUsersAreFullyShown(it.count, it.pubkeys) }
                ReactionPreviewStrip(
                    groups = groups,
                    profiles = profiles,
                    onUserClick = onUserClick,
                )
                EngagementFooter(
                    visible = onOpenLikes != null && !allReactionsShown,
                    label = "リアクションをすべて見る ($reactionCount)",
                    onClick = onOpenLikes,
                )
            }
            null -> Unit
        }
    }
}

private data class ReactionPreviewGroup(
    val emoji: String? = null,
    val imageUrl: String? = null,
    val shortcode: String? = null,
    val count: Int,
    val pubkeys: List<String>,
)

@Composable
private fun ReactionPreviewStrip(
    groups: List<ReactionPreviewGroup>,
    profiles: Map<String, NostrProfile>,
    onUserClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .heightIn(min = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        groups.forEachIndexed { index, group ->
            if (index > 0) Spacer(modifier = Modifier.width(12.dp))
            Box(modifier = Modifier.width(28.dp).heightIn(min = 30.dp), contentAlignment = Alignment.Center) {
                if (group.imageUrl != null && group.shortcode != null) {
                    CustomReactionLink(
                        reaction = CustomReaction(
                            shortcode = group.shortcode,
                            imageUrl = group.imageUrl,
                        ),
                        containerSize = 28.dp,
                        imageSize = 20.dp,
                    )
                } else {
                    Text(group.emoji.orEmpty(), fontSize = 18.sp, maxLines = 1)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy((-6).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                group.pubkeys.distinct().take(MaxPreviewUsers).forEach { pubkey ->
                    val userProfile = profiles[pubkey]
                    AvatarCircle(
                        pubkey = pubkey,
                        name = userProfile?.bestName,
                        pictureUrl = userProfile?.picture,
                        size = 24,
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            .clickable { onUserClick(pubkey) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EngagementUserGroup(
    emoji: @Composable () -> Unit,
    count: Int,
    pubkeys: List<String>,
    profiles: Map<String, NostrProfile>,
    onUserClick: (String) -> Unit,
) {
    val uniquePubkeys = pubkeys.distinct()
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(30.dp), contentAlignment = Alignment.Center) { emoji() }
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.widthIn(min = 30.dp).padding(start = 4.dp),
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy((-6).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            uniquePubkeys.take(MaxPreviewUsers).forEach { pubkey ->
                val userProfile = profiles[pubkey]
                AvatarCircle(
                    pubkey = pubkey,
                    name = userProfile?.bestName,
                    pictureUrl = userProfile?.picture,
                    size = 24,
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .clickable { onUserClick(pubkey) },
                )
            }
            val remaining = (count - uniquePubkeys.take(MaxPreviewUsers).size).coerceAtLeast(0)
            if (remaining > 0) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+$remaining",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EngagementFooter(
    visible: Boolean,
    label: String,
    onClick: (() -> Unit)?,
) {
    if (!visible || onClick == null) return
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EngagementPlaceholder(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun reactionGroupKey(event: NostrEvent): String = when {
    event.content.trim() == "+" -> LikeReactionGroupKey
    else -> event.toReactionOption()?.key.orEmpty()
}

private fun engagementUsersAreFullyShown(count: Int, pubkeys: List<String>): Boolean =
    count <= MaxPreviewUsers && pubkeys.distinct().size >= count

private const val LikeReactionGroupKey = "like"
private const val MaxPreviewUsers = 5

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
                onLongClick = {
                    CustomEmojiStore.requestOpenSearch(
                        shortcode = reaction.shortcode,
                        imageUrl = reaction.imageUrl,
                    )
                },
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
    onLongClick: (() -> Unit)? = null,
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
            .combinedClickable(
                enabled = (enabled && onClick != null) || onLongClick != null,
                onClick = {
                    if (enabled) onClick?.invoke()
                },
                onLongClick = onLongClick,
            )
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

internal data class UnicodeReactionEntry(
    val value: String,
    val keywords: List<String>,
)

internal val UNICODE_REACTION_CATALOG = listOf(
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

internal val EMOJI_SEARCH_KEYWORDS by lazy {
    UNICODE_REACTION_CATALOG.associate { it.value to it.keywords }
}

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

internal fun hasContentWarning(tags: List<List<String>>): Boolean =
    tags.any { it.firstOrNull() == "content-warning" }

@Composable
private fun SensitiveContentWarning(
    onReveal: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "⚠️ 閲覧注意",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        TextButton(onClick = onReveal) {
            Text("内容を表示")
        }
    }
}

@Composable
private fun QuotePreview(
    event: NostrEvent,
    profile: NostrProfile?,
    profiles: Map<String, NostrProfile>,
    onImageClick: (List<String>, Int) -> Unit,
    onNoteClick: ((eventId: String) -> Unit)? = null,
) {
    var sensitiveContentRevealed by remember(event.id) { mutableStateOf(false) }
    val parsedContent = remember(event.content) {
        parseNoteContent(event.content)
    }
    val contentWarningPresent = remember(event.tags) {
        hasContentWarning(event.tags)
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
                    .then(
                        if (onNoteClick != null) Modifier.clickable { onNoteClick(event.id) }
                        else Modifier
                    ),
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
                        .then(
                            if (onNoteClick != null) Modifier.clickable { onNoteClick(event.id) }
                            else Modifier
                        ),
                )
                Text(
                    text = formatTimestamp(event.createdAt, todayTimeOnly = true),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (contentWarningPresent && !sensitiveContentRevealed) {
            SensitiveContentWarning(
                onReveal = { sensitiveContentRevealed = true },
            )
        } else {
            if (parsedContent.textContent.isNotBlank()) {
                CollapsibleNoteText(
                    text = parsedContent.textContent,
                    style = MaterialTheme.typography.bodySmall,
                    customEmojis = event.tags.customEmojiMap(),
                    onProfileClick = { onNoteClick?.invoke(event.id) },
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
        enableCustomEmojiLinks = true,
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
private const val TimelineGridImageMaxDecodeSizePx = 360

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
                animate = false,
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
        alignment = Alignment.Center,
        maxDecodeSizePx = TimelineGridImageMaxDecodeSizePx,
        filterQuality = FilterQuality.Low,
        animate = false,
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
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Box(
            modifier = if (onClick != null) {
                Modifier
                    .size(32.dp)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
            } else {
                Modifier.size(32.dp)
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
        Box(
            modifier = if (onCountClick != null) {
                Modifier
                    .height(32.dp)
                    .widthIn(min = 32.dp)
                    .clickable(onClick = onCountClick)
            } else {
                Modifier.widthIn(min = 20.dp)
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = countText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(y = (-1).dp),
                maxLines = 1,
                softWrap = false,
            )
        }
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
