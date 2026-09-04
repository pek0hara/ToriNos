package com.nostr.torinos.ui.notification

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.network.TargetLoadState
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.components.stripImageUrls
import com.nostr.torinos.ui.profile.AvatarCircle

@Composable
fun NotificationsDrawer(
    ownPubkey: String?,
    isOpen: Boolean,
    scrollToTopRequest: Int = 0,
    onUserClick: (String) -> Unit,
    onOpenTarget: (NotificationTargetDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(max = 360.dp),
    ) {
        if (ownPubkey == null) {
            EmptyNotifications(text = "通知を表示するには鍵を設定してください")
            return@ModalDrawerSheet
        }

        val muteStore = LocalAccountSession.current?.muteStore
        val viewModel: NotificationsViewModel = viewModel(
            key = "notifications-$ownPubkey",
            factory = viewModelFactory {
                initializer {
                    NotificationsViewModel(ownPubkey, muteStore)
                }
            },
        )
        val state by viewModel.state.collectAsState()
        val listState = rememberLazyListState()

        LaunchedEffect(isOpen, viewModel) {
            if (isOpen) {
                viewModel.startLiveSubscriptions()
            } else {
                viewModel.stopLiveSubscriptions()
            }
        }
        DisposableEffect(viewModel) {
            onDispose { viewModel.stopLiveSubscriptions() }
        }

        LaunchedEffect(scrollToTopRequest) {
            if (scrollToTopRequest > 0) {
                listState.scrollToItem(0)
            }
        }

        Column(modifier = Modifier.fillMaxHeight()) {
            Text(
                text = "通知",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            )
            HorizontalDivider()
            NotificationsList(
                state = state,
                listState = listState,
                onUserClick = onUserClick,
                onOpenTarget = onOpenTarget,
                onRetryTarget = viewModel::retryTarget,
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun NotificationsList(
    state: NotificationsState,
    listState: LazyListState,
    onUserClick: (String) -> Unit,
    onOpenTarget: (NotificationTargetDestination) -> Unit,
    onRetryTarget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
    ) {
        when {
            state.isInitialLoad && state.items.isEmpty() -> item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            state.items.isEmpty() -> item {
                EmptyNotifications(text = "通知はまだありません")
            }
            else -> {
                items(state.items, key = { it.id }) { item ->
                    NotificationRow(
                        item = item,
                        actorProfile = state.profiles[item.actorPubkey],
                        targetState = item.targetEventId?.let { state.targetStates[it] },
                        targetProfile = item.targetEventId
                            ?.let { state.targetEvents[it] }
                            ?.let { state.profiles[it.pubkey] },
                        onUserClick = onUserClick,
                        onOpenTarget = onOpenTarget,
                        onRetryTarget = onRetryTarget,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    item: NotificationItem,
    actorProfile: NostrProfile?,
    targetState: TargetLoadState?,
    targetProfile: NostrProfile?,
    onUserClick: (String) -> Unit,
    onOpenTarget: (NotificationTargetDestination) -> Unit,
    onRetryTarget: (String) -> Unit,
) {
    val destination = when (item.type) {
        NotificationType.Reply -> item.event?.let(::notificationTargetDestination)
        NotificationType.Repost,
        NotificationType.Like -> (targetState as? TargetLoadState.Resolved)?.event?.let(::notificationTargetDestination)
        NotificationType.Follow -> null
    }
    val accent = when (item.type) {
        NotificationType.Reply -> MaterialTheme.colorScheme.primary
        NotificationType.Repost -> Color(0xFF2BAE66)
        NotificationType.Like -> Color(0xFFE17055)
        NotificationType.Follow -> MaterialTheme.colorScheme.tertiary
    }
    val icon = when (item.type) {
        NotificationType.Reply -> Icons.Default.MailOutline
        NotificationType.Repost -> Icons.Default.Repeat
        NotificationType.Like -> Icons.Default.Favorite
        NotificationType.Follow -> Icons.Default.PersonAdd
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = destination != null) { destination?.let(onOpenTarget) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCircle(
            pubkey = item.actorPubkey,
            name = actorProfile?.bestName,
            pictureUrl = actorProfile?.picture,
            size = 40,
            modifier = Modifier.clickable { onUserClick(item.actorPubkey) },
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.type == NotificationType.Like) {
                        NotificationReactionIcon(
                            event = item.event,
                            fallbackIcon = icon,
                            tint = accent,
                        )
                    } else {
                        NotificationIcon(icon = icon, tint = accent)
                    }
                    ProfileNameText(
                        profile = actorProfile,
                        fallback = shortPubkey(item.actorPubkey),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = notificationTitleSuffix(item.type),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (item.createdAt != null) {
                    Text(
                        text = formatTimestamp(item.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (item.type == NotificationType.Reply) {
                val replyText = item.event?.content?.previewText().orEmpty()
                if (replyText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = replyText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (item.type != NotificationType.Follow) {
                Spacer(modifier = Modifier.height(6.dp))
                NotificationTargetPreview(
                    reference = item.targetReference,
                    state = targetState,
                    profile = targetProfile,
                    onRetry = onRetryTarget,
                    onOpenTarget = onOpenTarget,
                )
            }
        }
    }
}

@Composable
private fun NotificationReactionIcon(
    event: NostrEvent?,
    fallbackIcon: ImageVector,
    tint: Color,
) {
    val customReaction = event?.toCustomReaction()
    val reactionContent = event?.content?.trim().orEmpty()
    when {
        customReaction != null -> NetworkImage(
            url = customReaction.imageUrl,
            contentDescription = ":${customReaction.shortcode}:",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(18.dp),
        )
        reactionContent == "-" -> Text(
            text = "👎",
            fontSize = 18.sp,
            lineHeight = 18.sp,
            maxLines = 1,
        )
        reactionContent.isNotEmpty() && reactionContent != "+" -> Text(
            text = reactionContent,
            fontSize = 18.sp,
            lineHeight = 18.sp,
            maxLines = 1,
        )
        else -> NotificationIcon(icon = fallbackIcon, tint = tint)
    }
}

@Composable
private fun NotificationIcon(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = tint,
    )
}

@Composable
private fun EmptyNotifications(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun notificationTitleSuffix(type: NotificationType): String =
    when (type) {
        NotificationType.Reply -> "から返信"
        NotificationType.Repost -> "がリポスト"
        NotificationType.Like -> "がいいね"
        NotificationType.Follow -> "がフォロー"
    }

private fun String.previewText(): String =
    stripImageUrls(stripNostrEventUris(this))
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(120)

private fun shortPubkey(pubkey: String): String = pubkey.take(8) + "..." + pubkey.takeLast(8)
