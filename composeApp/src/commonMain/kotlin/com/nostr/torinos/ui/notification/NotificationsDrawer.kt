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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.components.stripImageUrls
import com.nostr.torinos.ui.profile.AvatarCircle

@Composable
fun NotificationsDrawer(
    ownPubkey: String?,
    scrollToTopRequest: Int = 0,
    onUserClick: (String) -> Unit,
    onOpenThread: (String) -> Unit,
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

        val viewModel: NotificationsViewModel = viewModel(
            key = "notifications-$ownPubkey",
            factory = viewModelFactory { initializer { NotificationsViewModel(ownPubkey) } },
        )
        val state by viewModel.state.collectAsState()
        val listState = rememberLazyListState()

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
                onOpenThread = onOpenThread,
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
    onOpenThread: (String) -> Unit,
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
                        targetEvent = item.targetEventId?.let { state.targetEvents[it] },
                        targetProfile = item.targetEventId
                            ?.let { state.targetEvents[it] }
                            ?.let { state.profiles[it.pubkey] },
                        onUserClick = onUserClick,
                        onOpenThread = onOpenThread,
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
    targetEvent: NostrEvent?,
    targetProfile: NostrProfile?,
    onUserClick: (String) -> Unit,
    onOpenThread: (String) -> Unit,
) {
    val threadTargetId = when (item.type) {
        NotificationType.Reply -> item.event.id
        NotificationType.Repost,
        NotificationType.Like -> item.targetEventId
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
            .clickable(enabled = threadTargetId != null) { threadTargetId?.let(onOpenThread) }
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
                    NotificationIcon(icon = icon, tint = accent)
                    Text(
                        text = notificationTitle(item.type, actorProfile?.bestName, item.actorPubkey),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = formatTimestamp(item.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (item.type == NotificationType.Reply) {
                val replyText = item.event.content.previewText()
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
                Text(
                    text = targetPreviewText(targetEvent, targetProfile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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

private fun notificationTitle(type: NotificationType, actorName: String?, actorPubkey: String): String {
    val name = actorName ?: shortPubkey(actorPubkey)
    return when (type) {
        NotificationType.Reply -> "$name から返信"
        NotificationType.Repost -> "$name がリポスト"
        NotificationType.Like -> "$name がいいね"
        NotificationType.Follow -> "$name がフォロー"
    }
}

private fun targetPreviewText(event: NostrEvent?, profile: NostrProfile?): String {
    if (event == null) return "対象ポストを読み込み中"
    val author = profile?.bestName ?: shortPubkey(event.pubkey)
    val body = event.content.previewText()
    return if (body.isBlank()) "$author のポスト" else "$author: $body"
}

private fun String.previewText(): String =
    stripImageUrls(stripNostrEventUris(this))
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(120)

private fun shortPubkey(pubkey: String): String = pubkey.take(8) + "..." + pubkey.takeLast(8)
