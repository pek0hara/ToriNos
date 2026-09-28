package com.nostr.torinos.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nostr.torinos.article.reactionOptionForKey
import com.nostr.torinos.engagement.displayOwnEmojiReactionEventIds
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.hasOwnReaction
import com.nostr.torinos.engagement.isReactionPending
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.QuickReactionMenu
import com.nostr.torinos.ui.components.StandardEmojiPickerSheet

/**
 * 記事詳細の下部に常に出す「いいね」と「コメント」の2つのボタン。
 * いいねの長押しで絵文字リアクションを選べる。[actions]がnull（未ログイン）なら件数の表示だけを行う。
 */
@Composable
internal fun ArticleEngagementBar(
    engagement: ArticleEngagementState,
    actions: ArticleReactionActions?,
    onOpenComments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = engagement as? ArticleEngagementState.Loaded
    val reactions = loaded?.reactions
    var showQuickMenu by remember { mutableStateOf(false) }
    var showStandardEmojiPicker by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(EngagementBarHeight)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ArticleLikeButton(
                        reactions = reactions,
                        actions = actions,
                        onLongPress = { showQuickMenu = true },
                    )
                    if (actions != null && reactions != null) {
                        QuickReactionMenu(
                            expanded = showQuickMenu,
                            selectedReactionKeys = reactions.displayOwnEmojiReactionEventIds.keys,
                            onDismiss = { showQuickMenu = false },
                            onSelect = { option ->
                                showQuickMenu = false
                                actions.onReact(option)
                            },
                            onOpenStandardEmojiPicker = {
                                showQuickMenu = false
                                showStandardEmojiPicker = true
                            },
                        )
                    }
                }
                BarPillButton(
                    onClick = onOpenComments,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "💬", fontSize = 16.sp)
                    PillLabel(label = "コメント", count = loaded?.comments?.size)
                }
            }
        }
    }

    if (showStandardEmojiPicker && actions != null && reactions?.hasOwnReaction == false) {
        StandardEmojiPickerSheet(
            onDismiss = { showStandardEmojiPicker = false },
            onSelect = { option ->
                showStandardEmojiPicker = false
                markReactionUsed(option)
                actions.onReact(option)
            },
        )
    }
}

/**
 * いいねボタン。タップでいいねの切り替え、長押しで絵文字メニュー。自分が絵文字でリアクション済みなら
 * その絵文字を出し、タップで取り消す。送信中は次の操作を受け付けないため薄く表示する。
 */
@Composable
private fun ArticleLikeButton(
    reactions: NoteEngagementState?,
    actions: ArticleReactionActions?,
    onLongPress: () -> Unit,
) {
    val ownEmoji = reactions?.displayOwnEmojiReactionEventIds?.keys?.firstOrNull()?.let(::reactionOptionForKey)
    val isLiked = reactions != null && (
        reactions.ownLikeEventId != null ||
            reactions.pendingOperations[EngagementSlot.Reaction]?.request == EngagementRequest.AddLike
        )
    val isPending = reactions?.isReactionPending == true
    val canOperate = actions != null && reactions != null && !isPending
    val onClick: (() -> Unit)? = when {
        !canOperate || actions == null || reactions == null -> null
        ownEmoji != null -> { { actions.onUnreact(ownEmoji) } }
        isLiked -> actions.onUnlike
        reactions.hasOwnReaction -> null
        else -> actions.onLike
    }
    val onLongClick: (() -> Unit)? = if (canOperate && reactions?.hasOwnReaction == false) onLongPress else null
    BarPillButton(
        onClick = onClick,
        onLongClick = onLongClick,
        selected = ownEmoji != null || isLiked,
        modifier = Modifier.fillMaxWidth().alpha(if (isPending) PendingAlpha else 1f),
    ) {
        when (ownEmoji) {
            is ReactionOption.Custom -> NetworkImage(
                url = ownEmoji.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(18.dp),
            )
            is ReactionOption.Unicode -> Text(text = ownEmoji.value, fontSize = 16.sp, maxLines = 1)
            null -> Icon(
                if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = null,
                tint = if (isLiked) OwnReactionColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        PillLabel(label = "いいね", count = reactions?.reactionCount)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarPillButton(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .combinedClickable(
                enabled = onClick != null || onLongClick != null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        content()
    }
}

/** ラベルと件数。取得前（null）は件数を出さず、届いてもボタンの大きさは変わらない。 */
@Composable
private fun PillLabel(label: String, count: Int?) {
    Text(
        text = if (count == null) label else "$label $count",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

private fun markReactionUsed(option: ReactionOption) {
    when (option) {
        is ReactionOption.Unicode -> CustomEmojiStore.markUnicodeUsed(option.value)
        is ReactionOption.Custom -> CustomEmojiStore.markCustomReactionUsed(option.shortcode, option.imageUrl)
    }
}

/** 下部バーの高さ（ナビゲーションバーの余白を除く）。本文の下余白の計算にも使う。 */
internal val EngagementBarHeight = 52.dp

private const val PendingAlpha = 0.45f

/** 投稿カードで自分のリアクションを示す色と同じ。 */
private val OwnReactionColor = Color(0xFFE17055)

/** 下部バーの💬から開くコメントシート。半分の高さで開き、引き上げると全画面になる。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArticleCommentsSheet(
    engagement: ArticleEngagementState,
    actions: ArticleReactionActions?,
    onUserClick: (pubkey: String) -> Unit,
    onComment: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            articleEngagementItems(
                engagement = engagement,
                onUserClick = onUserClick,
                reactionActions = actions,
                onComment = onComment,
                onRetry = onRetry,
            )
        }
    }
}
