package com.nostr.torinos.ui.components

import com.nostr.torinos.model.*

import com.nostr.torinos.model.isLikeReaction
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.isUnicodeEmojiReactionContent
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toReactionOption
import com.nostr.torinos.ui.profile.AvatarCircle
import kotlin.math.max

internal enum class ExpandedEngagement {
    Replies,
    Reposts,
    Reactions,
}

internal fun ExpandedEngagement?.toggled(target: ExpandedEngagement): ExpandedEngagement? =
    if (this == target) null else target

@Composable
internal fun EngagementDetailsPanel(
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
    quoteRepostEvents: List<NostrEvent>,
    profiles: Map<String, NostrProfile>,
    onUserClick: (String) -> Unit,
    onOpenReplies: (() -> Unit)?,
    onOpenLikes: (() -> Unit)?,
    onOpenReposts: (() -> Unit)?,
    onOpenNote: ((String) -> Unit)?,
    onImageClick: (List<String>, Int) -> Unit,
    showImagePreview: Boolean = true,
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
                        showImagePreview = showImagePreview,
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
                quoteRepostEvents.take(3).forEach { quoteRepost ->
                    QuotePreview(
                        event = quoteRepost,
                        profile = profiles[quoteRepost.pubkey],
                        profiles = profiles,
                        onImageClick = onImageClick,
                        onNoteClick = onOpenNote,
                        showImagePreview = showImagePreview,
                    )
                }
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

internal data class ReactionPreviewGroup(
    val emoji: String? = null,
    val imageUrl: String? = null,
    val shortcode: String? = null,
    val count: Int,
    val pubkeys: List<String>,
)

@Composable
internal fun ReactionPreviewStrip(
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
            Box(modifier = Modifier.widthIn(min = 28.dp, max = 160.dp).heightIn(min = 30.dp), contentAlignment = Alignment.Center) {
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
                    val content = group.emoji.orEmpty()
                    Text(
                        text = content,
                        fontSize = if (content.isUnicodeEmojiReactionContent()) 18.sp else 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
internal fun EngagementUserGroup(
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
internal fun EngagementFooter(
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
internal fun EngagementPlaceholder(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun reactionGroupKey(event: NostrEvent): String = when {
    event.isLikeReaction() -> LikeReactionGroupKey
    else -> event.toReactionOption()?.key.orEmpty()
}

internal fun engagementUsersAreFullyShown(count: Int, pubkeys: List<String>): Boolean =
    count <= MaxPreviewUsers && pubkeys.distinct().size >= count

internal const val LikeReactionGroupKey = "like"
internal const val MaxPreviewUsers = 5

@Composable
internal fun ReactionSummaryRow(
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
    val openCustomEmoji = LocalCustomEmojiNavigator.current

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
            val option = ReactionOption.Custom(reaction.shortcode, reaction.imageUrl, reaction.setAddress)
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
                    openCustomEmoji(CustomEmojiOpenRequest.of(reaction.shortcode, reaction.imageUrl, reaction.setAddress))
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
                        modifier = Modifier.widthIn(min = 28.dp, max = 160.dp),
                        fontSize = if (reaction.content.isUnicodeEmojiReactionContent()) 18.sp else 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
internal fun ReactionChip(
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

internal data class ReactionChipColors(
    val background: Color,
    val content: Color,
    val border: Color,
    val selectedBackground: Color,
    val selectedContent: Color,
    val selectedBorder: Color,
)

internal fun reactionChipColors(darkTheme: Boolean): ReactionChipColors =
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
internal fun QuickReactionMenu(
    expanded: Boolean,
    selectedReactionKeys: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (ReactionOption) -> Unit,
    onOpenStandardEmojiPicker: () -> Unit,
) {
    val accountSession = LocalAccountSession.current
    val openCustomEmoji = LocalCustomEmojiNavigator.current
    val emojiPreferences = rememberEmojiPreferences()
    val recentOptions = remember(emojiPreferences) {
        emojiPreferences.recent
            .map { it.toReactionOption(emojiPreferences::setAddressOf) }
            .distinctBy { it.key }
            .take(16)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 右上の「絵文字セット」と1行に並べる。文字サイズを大きくしても折り返さないよう短くし、1行に収める。
                Text(
                    text = "最近使った",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                EmojiSetsButton(
                    onClick = {
                        onDismiss()
                        openCustomEmoji(CustomEmojiOpenRequest.of(""))
                    },
                )
            }
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
                                accountSession.recordReactionUse(option)
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
internal fun ReactionPickerTile(
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
    reaction: ReactionOption? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
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
            when (reaction) {
                is ReactionOption.Custom -> {
                    var imageFailed by remember(reaction.imageUrl) { mutableStateOf(false) }
                    if (imageFailed) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = contentDescription,
                            modifier = Modifier.size(22.dp),
                            tint = tint,
                        )
                    } else {
                        NetworkImage(
                            url = reaction.imageUrl,
                            contentDescription = contentDescription,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(22.dp),
                            onError = { imageFailed = true },
                        )
                    }
                }
                is ReactionOption.Unicode -> Text(
                    text = reaction.value,
                    fontSize = 22.sp,
                    maxLines = 1,
                    modifier = Modifier.semantics {
                        this.contentDescription = contentDescription
                    },
                )
                null -> Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(22.dp),
                    tint = tint,
                )
            }
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
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(y = (-1).dp),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

