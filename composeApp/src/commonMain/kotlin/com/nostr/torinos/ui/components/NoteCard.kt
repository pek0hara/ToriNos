package com.nostr.torinos.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.profile.AvatarCircle
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun NoteCard(
    event: NostrEvent,
    profile: NostrProfile?,
    replyCount: Int,
    reactionCount: Int,
    repostCount: Int = 0,
    isLiked: Boolean = false,
    onUserClick: (pubkey: String) -> Unit = {},
    onLike: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onRepost: (() -> Unit)? = null,
    ownPubkey: String? = null,
    onDelete: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCircle(
            pubkey = event.pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            modifier = Modifier.clickable { onUserClick(event.pubkey) },
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profile?.bestName ?: event.shortPubkey,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onUserClick(event.pubkey) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTimestamp(event.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (ownPubkey != null && event.pubkey == ownPubkey && onDelete != null) {
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
                                text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            val imageUrls = extractImageUrls(event.content)
            val textContent = if (imageUrls.isNotEmpty()) stripImageUrls(event.content) else event.content
            if (textContent.isNotBlank()) {
                LinkedText(
                    text = textContent,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            imageUrls.forEach { url ->
                Spacer(modifier = Modifier.height(8.dp))
                NetworkImage(
                    url = url,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EngagementCount(
                    icon = Icons.Default.MailOutline,
                    contentDescription = "返信",
                    count = replyCount,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onReply,
                )
                EngagementCount(
                    icon = Icons.Default.Repeat,
                    contentDescription = "リポスト",
                    count = repostCount,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onRepost,
                )
                EngagementCount(
                    icon = Icons.Default.Favorite,
                    contentDescription = "いいね",
                    count = reactionCount,
                    tint = if (isLiked) Color(0xFFE17055) else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onLike,
                )
            }
        }
    }
}

@Composable
fun EngagementCount(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    count: Int,
    tint: androidx.compose.ui.graphics.Color,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            onClick = onClick ?: {},
            enabled = onClick != null,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
        }
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun formatTimestamp(epochSeconds: Long): String = try {
    val local = Instant.fromEpochSeconds(epochSeconds)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    buildString {
        append(local.monthNumber.toString().padStart(2, '0'))
        append('/')
        append(local.dayOfMonth.toString().padStart(2, '0'))
        append(' ')
        append(local.hour.toString().padStart(2, '0'))
        append(':')
        append(local.minute.toString().padStart(2, '0'))
    }
} catch (_: Exception) {
    ""
}
