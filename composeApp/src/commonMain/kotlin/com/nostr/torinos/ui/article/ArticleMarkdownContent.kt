package com.nostr.torinos.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.article.MarkdownBlock
import com.nostr.torinos.article.MarkdownInline
import com.nostr.torinos.article.parseArticleMarkdown
import com.nostr.torinos.article.parseMarkdownInline
import com.nostr.torinos.emoji.customEmojiMap
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.extractImageUrls
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.components.stripImageUrls
import com.nostr.torinos.ui.profile.AvatarCircle

@Composable
internal fun MarkdownBody(
    content: String,
    articleQuoteIds: List<String>,
    quotedEvents: Map<String, NostrEvent>,
    quotedProfiles: Map<String, NostrProfile>,
    loadingQuoteIds: Set<String>,
    onUserClick: (pubkey: String) -> Unit,
    onNoteClick: (eventId: String) -> Unit,
) {
    val markdown = remember(content, articleQuoteIds) { parseArticleMarkdown(content, articleQuoteIds) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        markdown.sections.forEach { section ->
            section.block?.let { MarkdownBlockContent(it) }
            section.quoteIds.forEach { eventId ->
                ArticleQuotePreviewSlot(
                    eventId = eventId,
                    quotedEvents = quotedEvents,
                    quotedProfiles = quotedProfiles,
                    loadingQuoteIds = loadingQuoteIds,
                    onUserClick = onUserClick,
                    onNoteClick = onNoteClick,
                )
            }
        }
        markdown.trailingQuoteIds.forEach { eventId ->
            ArticleQuotePreviewSlot(
                eventId = eventId,
                quotedEvents = quotedEvents,
                quotedProfiles = quotedProfiles,
                loadingQuoteIds = loadingQuoteIds,
                onUserClick = onUserClick,
                onNoteClick = onNoteClick,
            )
        }
    }
}

@Composable
private fun MarkdownBlockContent(block: MarkdownBlock) {
    when (block) {
        MarkdownBlock.Blank -> Box(modifier = Modifier.height(6.dp))
        is MarkdownBlock.Heading -> MarkdownInlineText(
            text = block.text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (block.level == 1) FontWeight.Bold else FontWeight.SemiBold,
            headingLevel = block.level,
        )
        is MarkdownBlock.Quote -> MarkdownInlineText(
            text = block.text,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(6.dp),
                )
                .padding(10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is MarkdownBlock.ListItem -> MarkdownInlineText(
            text = "• ${block.text}",
            style = MaterialTheme.typography.bodyLarge,
        )
        is MarkdownBlock.Code -> Text(
            text = block.text,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(6.dp),
                )
                .padding(10.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is MarkdownBlock.Image -> NetworkImage(
            url = block.url,
            contentDescription = block.alt.takeIf { it.isNotBlank() },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit,
            maxDecodeSizePx = 1200,
        )
        is MarkdownBlock.Paragraph -> MarkdownInlineText(
            text = block.text,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}


@Composable
private fun ArticleQuotePreviewSlot(
    eventId: String,
    quotedEvents: Map<String, NostrEvent>,
    quotedProfiles: Map<String, NostrProfile>,
    loadingQuoteIds: Set<String>,
    onUserClick: (pubkey: String) -> Unit,
    onNoteClick: (eventId: String) -> Unit,
) {
    val quotedEvent = quotedEvents[eventId]
    if (quotedEvent != null) {
        ArticleQuotePreview(
            event = quotedEvent,
            profile = quotedProfiles[quotedEvent.pubkey],
            onUserClick = onUserClick,
            onNoteClick = { onNoteClick(eventId) },
        )
    } else if (eventId in loadingQuoteIds) {
        ArticleQuoteStatusPreview("引用投稿を読み込んでいます")
    } else {
        ArticleQuoteStatusPreview("引用投稿を読み込めませんでした")
    }
}

@Composable
private fun ArticleQuotePreview(
    event: NostrEvent,
    profile: NostrProfile?,
    onUserClick: (pubkey: String) -> Unit,
    onNoteClick: () -> Unit,
) {
    val imageUrls = remember(event.content) { extractImageUrls(event.content) }
    val textContent = remember(event.content) {
        stripImageUrls(stripNostrEventUris(event.content))
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
            .clickable(onClick = onNoteClick)
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
                modifier = Modifier.clickable { onUserClick(event.pubkey) },
            )
            ProfileNameText(
                profile = profile,
                fallback = event.shortPubkey,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onUserClick(event.pubkey) },
            )
            Text(
                text = formatTimestamp(event.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (textContent.isNotBlank()) {
            LinkedText(
                text = textContent,
                style = MaterialTheme.typography.bodySmall,
                customEmojis = event.tags.customEmojiMap(),
                onProfileClick = onUserClick,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        imageUrls.firstOrNull()?.let { imageUrl ->
            NetworkImage(
                url = imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop,
                maxDecodeSizePx = 720,
            )
        }
    }
}

@Composable
private fun ArticleQuoteStatusPreview(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.small,
            )
            .padding(10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MarkdownInlineText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    fontWeight: FontWeight? = null,
    headingLevel: Int? = null,
) {
    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
    )
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = MaterialTheme.colorScheme.surfaceVariant,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val textStyle = when (headingLevel) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        else -> style
    }.let { if (fontWeight != null) it.copy(fontWeight = fontWeight) else it }

    Text(
        text = markdownAnnotatedString(text, linkStyle, codeStyle),
        modifier = modifier,
        style = textStyle,
        color = color,
    )
}

private fun markdownAnnotatedString(
    text: String,
    linkStyle: TextLinkStyles,
    codeStyle: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    appendMarkdownInline(parseMarkdownInline(text), linkStyle, codeStyle)
}

private fun AnnotatedString.Builder.appendMarkdownInline(
    nodes: List<MarkdownInline>,
    linkStyle: TextLinkStyles,
    codeStyle: SpanStyle,
) {
    nodes.forEach { node ->
        when (node) {
            is MarkdownInline.Text -> append(node.text)
            is MarkdownInline.Code -> {
                pushStyle(codeStyle)
                append(node.text)
                pop()
            }
            is MarkdownInline.Bold -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendMarkdownInline(node.children, linkStyle, codeStyle)
                pop()
            }
            is MarkdownInline.Italic -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendMarkdownInline(node.children, linkStyle, codeStyle)
                pop()
            }
            is MarkdownInline.Link -> {
                pushLink(LinkAnnotation.Url(url = node.url, styles = linkStyle))
                appendMarkdownInline(node.children, linkStyle, codeStyle)
                pop()
            }
        }
    }
}
