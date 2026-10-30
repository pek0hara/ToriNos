package com.nostr.torinos.ui.components

import com.nostr.torinos.model.*


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.emoji.customEmojiMap
import com.nostr.torinos.ui.profile.AvatarCircle

@Composable
internal fun SensitiveContentWarning(
    onReveal: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "⚠️ 閲覧注意",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        TextButton(onClick = onReveal) {
            Text("内容を表示")
        }
    }
}

@Composable
internal fun QuotePreview(
    event: NostrEvent,
    profile: NostrProfile?,
    profiles: Map<String, NostrProfile>,
    onImageClick: (List<String>, Int) -> Unit,
    onNoteClick: ((eventId: String) -> Unit)? = null,
    showImagePreview: Boolean = true,
    /** 同じ引用が複数のカードに出ても再生状態を共有しないよう、引用元カードのIDを渡す。 */
    hostEventId: String = "",
) {
    var sensitiveContentRevealed by remember(event.id) { mutableStateOf(false) }
    var imagePreviewRevealed by remember(event.id) { mutableStateOf(false) }
    val parsedContent = remember(event.content, event.tags, event.kind) {
        parseNoteContent(event)
    }
    val contentWarningPresent = remember(event.tags) {
        hasContentWarning(event.tags)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.small,
            )
            .clip(MaterialTheme.shapes.small)
            .then(
                if (onNoteClick != null) Modifier.clickable { onNoteClick(event.id) }
                else Modifier
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarCircle(
                pubkey = event.pubkey,
                name = profile?.bestName,
                pictureUrl = profile?.picture,
                size = 24,
                modifier = Modifier
                    .then(
                        if (onNoteClick != null) Modifier.clickable { onNoteClick(event.id) }
                        else Modifier
                    ),
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ProfileNameText(
                        profile = profile,
                        fallback = event.shortPubkey,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .then(
                                if (onNoteClick != null) Modifier.clickable { onNoteClick(event.id) }
                                else Modifier
                            ),
                    )
                }
                Text(
                    text = formatTimestamp(event.createdAt, todayTimeOnly = true),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (contentWarningPresent && !sensitiveContentRevealed) {
            SensitiveContentWarning(
                onReveal = { sensitiveContentRevealed = true },
            )
        } else {
            if (parsedContent.textContent.isNotBlank()) {
                CollapsibleNoteText(
                    text = parsedContent.textContent,
                    style = MaterialTheme.typography.bodySmall,
                    customEmojis = event.tags.customEmojiMap(),
                    onProfileClick = { onNoteClick?.invoke(event.id) },
                    profiles = profiles,
                    onHashtagClick = null,
                )
            }
            HiddenTextContent(eventId = event.id, text = event.content)
            if (parsedContent.imageUrls.isNotEmpty()) {
                if (showImagePreview || imagePreviewRevealed) {
                    ImagePreviewGrid(
                        images = parsedContent.images,
                        singleImageMaxHeight = 180.dp,
                        onImageClick = onImageClick,
                    )
                } else {
                    PreviewLoadPlaceholder(
                        label = "画像 ${parsedContent.imageUrls.size}枚",
                        onReveal = { imagePreviewRevealed = true },
                    )
                }
            }
            parsedContent.playableMedia.forEach { media ->
                InlineMediaPlayer(
                    media = media,
                    sourceId = "$hostEventId>${event.id}",
                    showPoster = showImagePreview || imagePreviewRevealed,
                    maxHeight = 180.dp,
                )
            }
        }
    }
}

@Composable
internal fun CollapsibleNoteText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    customEmojis: Map<String, String> = emptyMap(),
    onProfileClick: (pubkey: String) -> Unit,
    profiles: Map<String, NostrProfile>,
    onHashtagClick: ((tag: String) -> Unit)?,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    var hasHiddenLines by remember(text) { mutableStateOf(false) }
    // 画像にするのはイベントのタグにある絵文字だけなので、1文字として数えるのもそれだけ。
    val customEmojiShortcodes = customEmojis.keys
    val collapsedTextLength = remember(text, customEmojiShortcodes) {
        countTextWithCustomEmojis(text, customEmojiShortcodes)
    }
    val exceedsCharacterLimit = collapsedTextLength > CollapsedTextCharacterLimit
    val displayedText = if (!expanded && exceedsCharacterLimit) {
        truncateTextPreservingWebUrlsAndCustomEmojis(
            text = text,
            maxLength = CollapsedTextCharacterLimit,
            customEmojiShortcodes = customEmojiShortcodes,
        )
    } else {
        text
    }
    val shouldShowToggle = expanded || exceedsCharacterLimit || hasHiddenLines

    LinkedText(
        text = displayedText,
        style = style,
        customEmojis = customEmojis,
        onProfileClick = onProfileClick,
        profiles = profiles,
        onHashtagClick = onHashtagClick,
        enableCustomEmojiLinks = true,
        maxLines = if (expanded) Int.MAX_VALUE else CollapsedTextMaxVisibleLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (!expanded) {
                hasHiddenLines = result.hasVisualOverflow
            }
        },
    )
    if (shouldShowToggle) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "閉じる" else "もっと見る")
        }
    }
}

internal const val CollapsedTextCharacterLimit = 140
internal const val CollapsedTextMaxVisibleLines = 9
