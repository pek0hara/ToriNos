package com.nostr.torinos.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.components.stripImageUrls

@Composable
fun MyProfileReactionsList(
    state: MyProfileReactionsState,
    onUserClick: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: LazyListScope.() -> Unit = {},
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        header()

        when {
            state.isInitialLoad && state.items.isEmpty() -> item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            state.items.isEmpty() -> item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "反応はまだありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                items(state.items, key = { it.id }) { item ->
                    ReactionRow(
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
private fun ReactionRow(
    item: MyProfileReactionItem,
    actorProfile: NostrProfile?,
    targetEvent: NostrEvent?,
    targetProfile: NostrProfile?,
    onUserClick: (String) -> Unit,
    onOpenThread: (String) -> Unit,
) {
    val targetId = when (item.type) {
        MyProfileReactionType.Reply -> item.event.id
        MyProfileReactionType.Repost,
        MyProfileReactionType.Like -> item.targetEventId
    }
    val accent = when (item.type) {
        MyProfileReactionType.Reply -> MaterialTheme.colorScheme.primary
        MyProfileReactionType.Repost -> Color(0xFF2BAE66)
        MyProfileReactionType.Like -> Color(0xFFE17055)
    }
    val icon = when (item.type) {
        MyProfileReactionType.Reply -> Icons.Default.MailOutline
        MyProfileReactionType.Repost -> Icons.Default.Repeat
        MyProfileReactionType.Like -> Icons.Default.Favorite
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = targetId != null) { targetId?.let(onOpenThread) }
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
                    ReactionIcon(icon = icon, tint = accent)
                    Text(
                        text = reactionTitle(item.type, actorProfile?.bestName, item.actorPubkey),
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

            if (item.type == MyProfileReactionType.Reply) {
                val replyText = item.event.content.previewText()
                if (replyText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = replyText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = targetPreviewText(targetEvent, targetProfile),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReactionIcon(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = tint,
    )
}

private fun reactionTitle(type: MyProfileReactionType, actorName: String?, actorPubkey: String): String {
    val name = actorName ?: shortPubkey(actorPubkey)
    return when (type) {
        MyProfileReactionType.Reply -> "$name から返信"
        MyProfileReactionType.Repost -> "$name がリポスト"
        MyProfileReactionType.Like -> "$name がいいね"
    }
}

private fun targetPreviewText(event: NostrEvent?, profile: NostrProfile?): String {
    if (event == null) return "対象ポストを読み込み中"
    val author = profile?.bestName ?: shortPubkey(event.pubkey)
    val body = event.content.previewText()
    return if (body.isBlank()) {
        "$author のポスト"
    } else {
        "$author: $body"
    }
}

private fun String.previewText(): String =
    stripImageUrls(stripNostrEventUris(this))
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(140)

private fun shortPubkey(pubkey: String): String = pubkey.take(8) + "..." + pubkey.takeLast(8)
