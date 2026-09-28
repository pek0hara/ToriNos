package com.nostr.torinos.ui.post

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nostr.torinos.emoji.customEmojiMap
import com.nostr.torinos.journal.activityTargetId
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toUnicodeReaction
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.components.stripImageUrls
import com.nostr.torinos.ui.profile.AvatarCircle

/** リポスト（kind 6）といいね（kind 7）の行。対象ポストの冒頭を添える。 */
@Composable
internal fun JournalActivityRow(
    event: NostrEvent,
    profile: NostrProfile?,
    targetEvent: NostrEvent?,
    targetProfile: NostrProfile?,
    onUserClick: (String) -> Unit,
    onOpenThread: (String) -> Unit,
) {
    val isRepost = event.kind == 6
    val targetId = event.activityTargetId()
    val accent = if (isRepost) Color(0xFF2BAE66) else Color(0xFFE17055)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = targetId != null) { targetId?.let(onOpenThread) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCircle(
            pubkey = event.pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 40,
            modifier = Modifier.clickable { onUserClick(event.pubkey) },
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
                    JournalReactionIcon(event = event, isRepost = isRepost, tint = accent)
                    ProfileNameText(
                        profile = profile,
                        fallback = event.shortPubkey,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = if (isRepost) "がリポスト" else "がいいね",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = formatTimestamp(event.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LinkedText(
                text = targetPreviewText(targetEvent, targetProfile),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                customEmojis = targetEvent?.tags?.customEmojiMap().orEmpty(),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun JournalReactionIcon(
    event: NostrEvent,
    isRepost: Boolean,
    tint: Color,
) {
    if (isRepost) {
        Icon(
            imageVector = Icons.Default.Repeat,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tint,
        )
        return
    }

    val customReaction = event.toCustomReaction()
    val unicodeReaction = event.toUnicodeReaction()
    when {
        customReaction != null -> NetworkImage(
            url = customReaction.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(18.dp),
        )
        unicodeReaction != null -> Box(
            modifier = Modifier.size(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = unicodeReaction.content,
                fontSize = 16.sp,
                lineHeight = 18.sp,
                maxLines = 1,
            )
        }
        else -> Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tint,
        )
    }
}

private fun targetPreviewText(event: NostrEvent?, profile: NostrProfile?): String {
    if (event == null) return "対象ポストを読み込み中"
    val author = profile?.bestName ?: event.shortPubkey
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
