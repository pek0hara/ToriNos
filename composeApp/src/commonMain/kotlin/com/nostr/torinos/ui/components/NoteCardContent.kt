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
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.ui.article.QuotedArticleCard
import com.nostr.torinos.emoji.customEmojiMap
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.article.canSelectForMatometr
import com.nostr.torinos.ui.settings.setPlainText
import kotlinx.coroutines.launch

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

    // Read inside AvatarBadges only, so badge updates do not recompose the whole card.
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
    var showRepostMenu by remember(event.id) { mutableStateOf(false) }
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
    val defaultReactionState = rememberDefaultReaction()
    val heartEnabled = when {
        isLiked -> onLike != null
        ownEmojiReaction != null -> onEmojiUnreact != null
        hasOwnReaction -> false
        else -> onLike != null
    }
    // デフォルトは押したときに読む。カードの描画は読み上げ用の説明だけが追う。
    val onHeartClick: (() -> Unit)? = if (heartEnabled) {
        {
            val action = heartTapAction(
                isLiked = isLiked,
                hasOwnEmojiReaction = ownEmojiReactionEventIds.isNotEmpty(),
                ownEmojiReaction = ownEmojiReaction,
                defaultReaction = defaultReactionState.value.takeIf { onEmojiReact != null },
            )
            when (action) {
                HeartTapAction.Like, HeartTapAction.Unlike -> onLike?.invoke()
                is HeartTapAction.React -> onEmojiReact?.invoke(action.option)
                is HeartTapAction.Unreact -> onEmojiUnreact?.invoke(action.option)
                HeartTapAction.None -> Unit
            }
        }
    } else {
        null
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
            AvatarBadges(badgesState, { selectedBadge = it }, { showBadges = true })
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

                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = nameRowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            ProfileNameText(
                                profile = profile,
                                fallback = event.shortPubkey,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                // 青はリンクと操作できる部分に絞る。名前は本文と同じ色の太字で見分ける。
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { onUserClick(event.pubkey) },
                            )
                        }
                        Text(
                            text = formatPostTimestamp(event.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            softWrap = false,
                            modifier = Modifier.padding(start = 8.dp, end = 2.dp),
                        )
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
                            val closeMenu = { showMenu = false }
                            val showModerationGroup = (isOwnPost && onDelete != null) || onHide != null ||
                                (!isOwnPost && (isMuted || onMute != null || onReport != null))
                            ActionDropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = closeMenu,
                            ) {
                                // よく使う操作
                                if (onQuote != null) {
                                    ActionDropdownMenuItem("投稿を引用", Icons.Outlined.FormatQuote, onClick = {
                                        closeMenu()
                                        onQuote(event)
                                    })
                                }
                                ActionDropdownMenuItem("本文をコピー", Icons.Outlined.ContentCopy, onClick = {
                                    closeMenu()
                                    coroutineScope.launch { clipboard.setPlainText(event.content) }
                                })
                                if (event.canSelectForMatometr() && matometrSession != null) {
                                    ActionDropdownMenuItem("まとめに追加", Icons.AutoMirrored.Outlined.PlaylistAdd, onClick = {
                                        closeMenu()
                                        matometrSession.matometrSelection.toggle(event)
                                    })
                                }
                                ActionDropdownMenuItem("データを表示", Icons.Outlined.DataObject, onClick = {
                                    closeMenu()
                                    showDetails = true
                                })
                                // 表示の制御・ブロック・通報・削除は誤操作しにくいよう末尾にまとめる
                                if (showModerationGroup) ActionDropdownMenuDivider()
                                if (onHide != null) {
                                    ActionDropdownMenuItem("このメッセージを非表示", Icons.Outlined.VisibilityOff, onClick = {
                                        closeMenu()
                                        onHide()
                                    })
                                }
                                if (!isOwnPost) {
                                    if (isMuted) {
                                        ActionDropdownMenuItem("ブロックを解除", Icons.Outlined.Block, onClick = {
                                            closeMenu()
                                            onUnmute?.invoke()
                                        })
                                    } else if (onMute != null) {
                                        ActionDropdownMenuItem("ユーザーをブロック", Icons.Outlined.Block, onClick = {
                                            closeMenu()
                                            onMute()
                                        })
                                    }
                                    if (onReport != null) {
                                        ActionDropdownMenuItem("通報", Icons.Outlined.Flag, destructive = true, onClick = {
                                            closeMenu()
                                            showReportDialog = true
                                        })
                                    }
                                }
                                if (isOwnPost && onDelete != null) {
                                    ActionDropdownMenuItem("削除", Icons.Outlined.Delete, destructive = true, onClick = {
                                        closeMenu()
                                        onDelete()
                                    })
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
                HiddenTextContent(eventId = event.id, text = event.content)
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
                // 引用を並べる投稿（ランキングなど）で1件が画面を占めないよう、最初の数件だけ出して残りは畳む。
                var showAllQuotes by remember(event.id) { mutableStateOf(false) }
                val visibleQuotes = if (showAllQuotes) quotedEvents else quotedEvents.take(CollapsedQuoteCount)
                visibleQuotes.forEach { quote ->
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
                val hiddenQuoteCount = quotedEvents.size - visibleQuotes.size
                if (hiddenQuoteCount > 0) {
                    InlineTextAction(
                        label = "他${hiddenQuoteCount}件の引用を表示",
                        onClick = { showAllQuotes = true },
                    )
                }
            }
            val client = event.clientName?.takeIf { it.isNotBlank() }
            // ＋ボタンを出さないので、リアクションが無ければ行ごと省く。
            val hasReactionChips = showReactions &&
                (reactionCount > 0 || isLiked || customReactions.isNotEmpty() || unicodeReactions.isNotEmpty())
            if (hasReactionChips || client != null) {
                Spacer(modifier = Modifier.height(8.dp))
                // リアクションの有無にかかわらず行を出し、右端に投稿したクライアントを添える。
                val via: (@Composable () -> Unit)? = client?.let { name ->
                    @Composable {
                        Text(
                            text = "via $name",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                    }
                }
                if (hasReactionChips) {
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
                        trailing = via,
                        showAddButton = false,
                        reactors = ReactorsSource(
                            events = reactionEvents,
                            onUserClick = onUserClick,
                            onLoad = onRefreshReactions,
                            onOpenAll = onOpenLikes,
                        ),
                    )
                } else if (via != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { via() }
                }
            }
            if (showEngagement) {
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    modifier = Modifier.offset(x = (-1).dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EngagementCount(
                        icon = Icons.Outlined.ChatBubbleOutline,
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
                    Box {
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
                            // 長押しでリポストと引用リポストを選べるようにする。
                            onLongClick = if (onRepost != null && onQuote != null) {
                                { showRepostMenu = true }
                            } else {
                                null
                            },
                            onCountClick = if (repostCount > 0) {
                                {
                                    expandedEngagement = expandedEngagement.toggled(ExpandedEngagement.Reposts)
                                }
                            } else {
                                onOpenReposts
                            },
                        )
                        ActionDropdownMenu(
                            expanded = showRepostMenu,
                            onDismissRequest = { showRepostMenu = false },
                        ) {
                            if (onRepost != null) {
                                ActionDropdownMenuItem(
                                    if (isReposted) "リポストを取り消す" else "リポスト",
                                    Icons.Default.Repeat,
                                    onClick = {
                                        showRepostMenu = false
                                        onRepost()
                                    },
                                )
                            }
                            if (onQuote != null) {
                                ActionDropdownMenuItem("引用リポスト", Icons.Outlined.FormatQuote, onClick = {
                                    showRepostMenu = false
                                    onQuote(event)
                                })
                            }
                        }
                    }
                    Box {
                        EngagementCount(
                            // 自分がまだ反応していない間は輪郭だけにして、押した状態と見分けられるようにする。
                            icon = if (hasOwnReaction) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            reaction = ownEmojiReaction.takeUnless { isLiked },
                            contentDescription = when {
                                isLiked -> "いいねを解除"
                                ownEmojiReaction != null -> "${ownEmojiReaction.eventContent}のリアクションを解除"
                                hasOwnReaction -> "リアクションを解除"
                                else -> defaultReactionState.value.takeIf { onEmojiReact != null }.heartContentDescription()
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
                    // リポストのカードでも、event は元の投稿なので元の作者へ送る。
                    com.nostr.torinos.ui.zap.ZapAction(event = event, authorProfile = profile, hidden = isMuted)
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
            defaultReactionMode = DefaultReactionPickerMode.Offer,
            onSelect = { option ->
                showStandardEmojiPicker = false
                accountSession.recordReactionUse(option)
                onEmojiReact?.invoke(option)
            },
        )
    }

}

@Composable
private fun AvatarBadges(
    badgesState: androidx.compose.runtime.State<List<com.nostr.torinos.badge.BadgeDisplayItem>>,
    onBadge: (com.nostr.torinos.badge.BadgeDisplayItem) -> Unit,
    onList: () -> Unit,
) {
    com.nostr.torinos.badge.AvatarBadgeGrid(badgesState.value, onBadge, onList)
}

/** 投稿カードで最初から表示する引用の件数。 */
private const val CollapsedQuoteCount = 2
