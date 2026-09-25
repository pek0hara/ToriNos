package com.nostr.torinos.ui.article

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.engagement.displayOwnEmojiReactionEventIds
import com.nostr.torinos.engagement.hasOwnReaction
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.QuickReactionMenu
import com.nostr.torinos.ui.components.StandardEmojiPickerSheet

/**
 * 記事詳細の下部に常に出すリアクションとコメントのバー。
 * [actions]がnull（未ログイン）なら件数の表示だけを行う。
 */
@Composable
internal fun ArticleEngagementBar(
    engagement: ArticleEngagementState,
    actions: ArticleReactionActions?,
    onOpenComments: () -> Unit,
    onComment: () -> Unit,
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
                    .padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ArticleReactionButton(reactions = reactions, actions = actions)
                if (actions != null && reactions != null && !reactions.hasOwnReaction) {
                    Box {
                        IconButton(onClick = { showQuickMenu = true }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "リアクションを追加",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                BarCount(
                    onClick = onOpenComments,
                    contentDescription = "コメントを見る",
                ) {
                    Icon(
                        Icons.Default.MailOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    CountText(loaded?.comments?.size)
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onComment) {
                    Text("コメントする")
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
 * いいねの切り替えとリアクション合計。自分が絵文字でリアクション済みならその絵文字を出し、
 * タップで取り消す。
 */
@Composable
private fun ArticleReactionButton(
    reactions: NoteEngagementState?,
    actions: ArticleReactionActions?,
) {
    val ownEmoji = reactions?.displayOwnEmojiReactionEventIds?.keys?.firstOrNull()?.let(::reactionOptionFromKey)
    val isLiked = reactions != null && (
        reactions.ownLikeEventId != null ||
            reactions.pendingOperations[EngagementSlot.Reaction]?.request == EngagementRequest.AddLike
        )
    val onClick: (() -> Unit)? = when {
        actions == null || reactions == null -> null
        ownEmoji != null -> { { actions.onUnreact(ownEmoji) } }
        isLiked -> actions.onUnlike
        reactions.hasOwnReaction -> null
        else -> actions.onLike
    }
    BarCount(
        onClick = onClick,
        contentDescription = when {
            ownEmoji != null || isLiked -> "リアクションを解除"
            else -> "いいね"
        },
    ) {
        when (ownEmoji) {
            is ReactionOption.Custom -> NetworkImage(
                url = ownEmoji.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(20.dp),
            )
            is ReactionOption.Unicode -> Text(text = ownEmoji.value, fontSize = 18.sp, maxLines = 1)
            null -> Icon(
                Icons.Default.Favorite,
                contentDescription = null,
                tint = if (isLiked) OwnReactionColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        CountText(reactions?.reactionCount)
    }
}

@Composable
private fun BarCount(
    onClick: (() -> Unit)?,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .widthIn(min = 56.dp)
            .height(40.dp)
            .let { base ->
                if (onClick != null) {
                    base.clickable(onClickLabel = contentDescription, onClick = onClick)
                } else {
                    base
                }
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

/** 取得前（null）は空欄にし、件数が届いてもバーの高さは変わらない。 */
@Composable
private fun CountText(count: Int?) {
    Text(
        text = count?.toString().orEmpty(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** リアクションのキー（`unicode:…`/`custom:shortcode:url`）から表示用の選択肢を復元する。 */
internal fun reactionOptionFromKey(key: String): ReactionOption? = when {
    key.startsWith("unicode:") -> ReactionOption.Unicode(key.removePrefix("unicode:"))
    key.startsWith("custom:") -> key.removePrefix("custom:").split(":", limit = 2)
        .takeIf { it.size == 2 }
        ?.let { (shortcode, url) -> ReactionOption.Custom(shortcode, url) }
    else -> null
}

private fun markReactionUsed(option: ReactionOption) {
    when (option) {
        is ReactionOption.Unicode -> CustomEmojiStore.markUnicodeUsed(option.value)
        is ReactionOption.Custom -> CustomEmojiStore.markCustomReactionUsed(option.shortcode, option.imageUrl)
    }
}

/** 下部バーの高さ（ナビゲーションバーの余白を除く）。本文の下余白の計算にも使う。 */
internal val EngagementBarHeight = 52.dp

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
