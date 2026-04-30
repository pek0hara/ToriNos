package com.nostr.torinos.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.ui.profile.AvatarCircle
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable
fun NoteCard(
    event: NostrEvent,
    profile: NostrProfile?,
    repostedByPubkey: String? = null,
    repostedByProfile: NostrProfile? = null,
    replyCount: Int,
    reactionCount: Int,
    repostCount: Int = 0,
    isLiked: Boolean = false,
    isReposted: Boolean = false,
    onUserClick: (pubkey: String) -> Unit = {},
    onLike: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onOpenReplies: (() -> Unit)? = null,
    onOpenLikes: (() -> Unit)? = null,
    onOpenReposts: (() -> Unit)? = null,
    onRepost: (() -> Unit)? = null,
    quotedEvents: List<QuotedEvent> = emptyList(),
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
                    Text(
                        text = "${repostedByProfile?.bestName ?: shortPubkey(repostedByPubkey)} がリポスト",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

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
            val contentWithoutQuotes = stripNostrEventUris(event.content)
            val textContent = if (imageUrls.isNotEmpty()) {
                stripImageUrls(contentWithoutQuotes)
            } else {
                contentWithoutQuotes
            }
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
            quotedEvents.forEach { quote ->
                Spacer(modifier = Modifier.height(8.dp))
                QuotePreview(
                    event = quote.event,
                    profile = quote.profile,
                    onUserClick = onUserClick,
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
                    onClick = if (replyCount > 0) onOpenReplies ?: onReply else onReply,
                    onCountClick = if (replyCount > 0) onOpenReplies else null,
                )
                EngagementCount(
                    icon = Icons.Default.Repeat,
                    contentDescription = "リポスト",
                    count = repostCount,
                    tint = if (isReposted) Color(0xFF2BAE66) else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onRepost,
                    onCountClick = onOpenReposts,
                )
                EngagementCount(
                    icon = Icons.Default.Favorite,
                    contentDescription = "いいね",
                    count = reactionCount,
                    tint = if (isLiked) Color(0xFFE17055) else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onLike,
                    onCountClick = onOpenLikes,
                )
            }
        }
    }
}

data class QuotedEvent(
    val event: NostrEvent,
    val profile: NostrProfile?,
)

@Composable
private fun QuotePreview(
    event: NostrEvent,
    profile: NostrProfile?,
    onUserClick: (pubkey: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.small,
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = profile?.bestName ?: event.shortPubkey,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onUserClick(event.pubkey) },
            )
            Text(
                text = formatTimestamp(event.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val imageUrls = extractImageUrls(event.content)
        val textContent = if (imageUrls.isNotEmpty()) {
            stripImageUrls(stripNostrEventUris(event.content))
        } else {
            stripNostrEventUris(event.content)
        }
        if (textContent.isNotBlank()) {
            LinkedText(
                text = textContent,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        imageUrls.firstOrNull()?.let { url ->
            NetworkImage(
                url = url,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp),
            )
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
    onCountClick: (() -> Unit)? = null,
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
            modifier = Modifier.clickable(enabled = onCountClick != null) { onCountClick?.invoke() },
        )
    }
}

fun formatTimestamp(epochSeconds: Long): String = try {
    val local = Instant.fromEpochSeconds(epochSeconds)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    buildString {
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
