package com.nostr.torinos.ui.components

import com.nostr.torinos.model.*


import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.nostr.torinos.ui.theme.LocalAppFontScale
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.ui.article.QuotedArticleCard
import com.nostr.torinos.emoji.customEmojiMap
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.article.canSelectForMatometr
import com.nostr.torinos.ui.settings.setPlainText
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
internal fun NoteCardContent(
    model: NoteCardModel,
    actions: NoteCardActions,
    matometrSession: com.nostr.torinos.account.AccountSession?,
    onQuote: ((NostrEvent) -> Unit)?,
) {
    val event = model.event
    val profile = model.author.profile
    val repostedByPubkey = model.author.repostedByPubkey
    val repostedByProfile = model.author.repostedByProfile
    val profiles = model.profiles
    val replyCount = model.engagement.replyCount
    val replies = model.engagement.replies
    val reactionCount = model.engagement.reactionCount
    val likeReactionCount = model.engagement.likeReactionCount
    val customReactions = model.engagement.customReactions
    val unicodeReactions = model.engagement.unicodeReactions
    val reactionEvents = model.engagement.reactionEvents
    val repostCount = model.engagement.repostCount
    val repostPubkeys = model.engagement.repostPubkeys
    val quoteRepostEvents = model.engagement.quoteRepostEvents
    val isLiked = model.engagement.isLiked
    val isReposted = model.engagement.isReposted
    val ownEmojiReactionEventIds = model.engagement.ownEmojiReactionEventIds
    val onUserClick = actions.onUserClick
    val onLike = actions.onLike
    val onEmojiReact = actions.onEmojiReact
    val onEmojiUnreact = actions.onEmojiUnreact
    val onReply = actions.onReply
    val onOpenReplies = actions.onOpenReplies
    val onOpenLikes = actions.onOpenLikes
    val onOpenReposts = actions.onOpenReposts
    val onRefreshReactions = actions.onRefreshReactions
    val onRepost = actions.onRepost
    val onHashtagClick = actions.onHashtagClick
    val quotedEvents = model.references.quotedEvents
    val replyParent = model.references.replyParent
    val ownPubkey = model.author.ownPubkey
    val onDelete = actions.onDelete
    val onHide = actions.onHide
    val isMuted = model.isMuted
    val onMute = actions.onMute
    val onUnmute = actions.onUnmute
    val onReport = actions.onReport
    val onNoteClick = actions.onNoteClick
    val onQuotedNoteClick = actions.onQuotedNoteClick
    val onReplyParentClick = actions.onReplyParentClick
    val precomputedContent = model.precomputedContent
    val deferWebViewLoad = model.policy.deferWebViewLoad
    val showEngagement = model.policy.showEngagement
    val showReactions = model.policy.showReactions
    val showImagePreviews = model.policy.showImagePreviews
    val showXPreviews = model.policy.showXPreviews

    // Read inside the name row only, so badge updates do not recompose the whole card.
    val badgesState = com.nostr.torinos.badge.rememberBadges(event.pubkey)
    var selectedBadge by remember(event.id, event.pubkey) { mutableStateOf<com.nostr.torinos.badge.BadgeDisplayItem?>(null) }
    var showBadges by remember(event.id, event.pubkey) { mutableStateOf(false) }
    selectedBadge?.let { badge ->
        com.nostr.torinos.badge.BadgeDetailDialog(badge, { selectedBadge = null }, onUserClick)
    }
    if (showBadges) com.nostr.torinos.badge.BadgeListSheet(event.pubkey, { showBadges = false }, onUserClick)
    var showMenu by remember(event.id) { mutableStateOf(false) }
    var showDetails by remember(event.id) { mutableStateOf(false) }
    var showReportDialog by remember(event.id) { mutableStateOf(false) }
    var showHeartReactionMenu by remember(event.id) { mutableStateOf(false) }
    var showStandardEmojiPicker by remember(event.id) { mutableStateOf(false) }
    var expandedImageState by remember(event.id) { mutableStateOf<ExpandedImageState?>(null) }
    var expandedEngagement by remember(event.id) { mutableStateOf<ExpandedEngagement?>(null) }
    var sensitiveContentRevealed by remember(event.id) { mutableStateOf(false) }
    var imagePreviewRevealed by remember(event.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val isOwnPost = ownPubkey != null && event.pubkey == ownPubkey
    val hasOwnReaction = isLiked || ownEmojiReactionEventIds.isNotEmpty()

    if (showDetails) {
        EventDataDialog(
            event = event,
            title = "投稿のデータ",
            onDismiss = { showDetails = false },
        )
    }

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
    val parsedContent = precomputedContent ?: remember(event.content, event.tags, event.kind) {
        parseNoteContent(event)
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
        Column(
            modifier = Modifier.widthIn(min = 42.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AvatarCircle(
                pubkey = event.pubkey,
                name = profile?.bestName,
                pictureUrl = profile?.picture,
                size = 42,
                modifier = Modifier.clickable { onUserClick(event.pubkey) },
            )
            Text(
                text = formatPostTimestamp(event.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                softWrap = false,
            )
            com.nostr.torinos.ui.article.MatometrSelectionCheckbox(event)
        }

        Column(modifier = Modifier.weight(1f)) {
            // Keep attribution and author controls within the avatar's height at normal font sizes.
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                val nameRowHeight = if (repostedByPubkey != null) 24.dp else 42.dp
                if (repostedByPubkey != null) {
                    Row(
                        modifier = Modifier
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
                            text = "${repostedByProfile?.bestName ?: shortNotePubkey(repostedByPubkey)} がリポスト",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            customEmojis = repostedByProfile?.customEmojis.orEmpty(),
                            enableWebLinks = false,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = nameRowHeight), contentAlignment = Alignment.CenterStart) {
                    val density = LocalDensity.current
                    // 名前欄に残す幅は、OSとアプリ設定の両方で大きくした文字に合わせる。
                    val textScale = density.fontScale * LocalAppFontScale.current
                    val textMeasurer = rememberTextMeasurer()
                    val labelStyle = MaterialTheme.typography.labelSmall
                    val badges = badgesState.value
                    val client = event.clientName?.takeIf { it.isNotBlank() }
                    // Text widths only depend on the string, style and density; measure them once per change.
                    val measuredClientWidth = remember(client, labelStyle, density) {
                        client?.let { with(density) { textMeasurer.measure(it, labelStyle).size.width.toDp() } }
                    }
                    val clientWidth = measuredClientWidth
                        ?.coerceAtMost((64f * textScale).dp)
                        ?.coerceAtMost(maxWidth * 0.25f) ?: 0.dp
                    val availableWidth = (maxWidth - (if (hasMenu) 24.dp else 0.dp) - clientWidth -
                        (if (client != null) 4.dp else 0.dp)).coerceAtLeast(0.dp)
                    val overflowWidth = remember(badges.size, labelStyle, density) {
                        with(density) { textMeasurer.measure("+${badges.size}", labelStyle).size.width.toDp().value }
                    }
                    val layout = com.nostr.torinos.badge.badgeStripLayout(badges.size, availableWidth.value, textScale, overflowWidth)
                    val badgeWidth = com.nostr.torinos.badge.badgeStripWidth(layout, overflowWidth)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ProfileNameText(
                            profile = profile,
                            fallback = event.shortPubkey,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = (availableWidth - badgeWidth.dp - if (badgeWidth > 0) 8.dp else 0.dp).coerceAtLeast(0.dp))
                                .clickable { onUserClick(event.pubkey) },
                        )
                        if (badgeWidth > 0) {
                            Spacer(Modifier.width(8.dp))
                            com.nostr.torinos.badge.BadgeStrip(
                                badges, layout, { selectedBadge = it }, { showBadges = true },
                                itemHeight = nameRowHeight,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (client != null) {
                            Spacer(Modifier.width(4.dp))
                            Text(client, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(max = clientWidth), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        if (hasMenu) Box {
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
                                    text = { Text("データを表示") },
                                    onClick = {
                                        showMenu = false
                                        showDetails = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("本文をコピー") },
                                    onClick = {
                                        showMenu = false
                                        coroutineScope.launch {
                                            clipboard.setPlainText(event.content)
                                        }
                                    },
                                )
                                if (event.canSelectForMatometr() && matometrSession != null) {
                                    DropdownMenuItem(text = { Text("まとめに追加") }, onClick = {
                                        showMenu = false
                                        matometrSession.matometrSelection.toggle(event)
                                    })
                                }
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
                                if (onHide != null) {
                                    DropdownMenuItem(
                                        text = { Text("このメッセージを非表示") },
                                        onClick = {
                                            showMenu = false
                                            onHide()
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
                    showImagePreview = showImagePreviews,
                    hostEventId = event.id,
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
                    if (showImagePreviews || imagePreviewRevealed) {
                        ImagePreviewGrid(
                            images = parsedContent.images,
                            onImageClick = { urls, index -> expandedImageState = ExpandedImageState(urls, index) },
                        )
                    } else {
                        PreviewLoadPlaceholder(
                            label = "画像 ${parsedContent.imageUrls.size}枚",
                            onReveal = { imagePreviewRevealed = true },
                        )
                    }
                }
                parsedContent.playableMedia.forEach { media ->
                    Spacer(modifier = Modifier.height(8.dp))
                    InlineMediaPlayer(
                        media = media,
                        sourceId = event.id,
                        showPoster = showImagePreviews || imagePreviewRevealed,
                    )
                }
                parsedContent.linkPreviewUrl?.let { url ->
                    LinkPreviewCard(
                        url = url,
                        deferWebViewLoad = deferWebViewLoad,
                        showXPreview = showXPreviews,
                        showImagePreview = showImagePreviews,
                    )
                }
                parsedContent.articleQuotes.forEach { reference ->
                    Spacer(modifier = Modifier.height(8.dp))
                    QuotedArticleCard(reference)
                }
                quotedEvents.forEach { quote ->
                    Spacer(modifier = Modifier.height(8.dp))
                    QuotePreview(
                        event = quote.event,
                        profile = quote.profile,
                        profiles = profiles,
                        onImageClick = { urls, index -> expandedImageState = ExpandedImageState(urls, index) },
                        onNoteClick = onQuotedNoteClick,
                        showImagePreview = showImagePreviews,
                        hostEventId = event.id,
                    )
                }
            }
            if (showReactions && reactionCount > 0) {
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
            if (showEngagement) {
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    modifier = Modifier.offset(x = (-1).dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                            reaction = ownEmojiReaction.takeUnless { isLiked },
                            contentDescription = when {
                                isLiked -> "いいねを解除"
                                ownEmojiReaction != null -> "${ownEmojiReaction.eventContent}のリアクションを解除"
                                hasOwnReaction -> "リアクションを解除"
                                else -> "いいね"
                            },
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
                        quoteRepostEvents = quoteRepostEvents,
                        profiles = profiles,
                        onUserClick = onUserClick,
                        onOpenReplies = onOpenReplies,
                        onOpenLikes = onOpenLikes,
                        onOpenReposts = onOpenReposts,
                        onOpenNote = onNoteClick,
                        onImageClick = { urls, index ->
                            expandedImageState = ExpandedImageState(urls, index)
                        },
                        showImagePreview = showImagePreviews,
                    )
                }
            }
        }
    }

    if (showStandardEmojiPicker && !hasOwnReaction) {
        val accountSession = LocalAccountSession.current
        StandardEmojiPickerSheet(
            onDismiss = { showStandardEmojiPicker = false },
            onSelect = { option ->
                showStandardEmojiPicker = false
                accountSession.recordReactionUse(option)
                onEmojiReact?.invoke(option)
            },
        )
    }

}
