package com.nostr.torinos.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.network.CustomEmojiStore

private val hashtagTextRegex = Regex("""(?<![\p{L}\p{N}_])#[\p{L}\p{N}_]+""")
private val emojiCodeRegex = Regex(""":([a-zA-Z0-9_-]+):""")

/** テキスト内のURLをクリッカブルリンクにして表示する。登録済みカスタム絵文字(:shortcode:)はインライン画像に置換する */
@Composable
fun LinkedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    customEmojis: Map<String, String> = emptyMap(),
    onProfileClick: ((pubkey: String) -> Unit)? = null,
    profiles: Map<String, NostrProfile> = emptyMap(),
    onHashtagClick: ((tag: String) -> Unit)? = null,
    enableWebLinks: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val emojis by CustomEmojiStore.emojis.collectAsState()
    val emojiMap = remember(emojis, customEmojis) {
        emojis.associate { it.shortcode to it.imageUrl } + customEmojis
    }

    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline,
        ),
    )
    val includeProfiles = onProfileClick != null
    val includeHashtags = onHashtagClick != null
    val links = remember(text, includeProfiles, includeHashtags, enableWebLinks) {
        buildList {
            if (enableWebLinks) {
                extractClickableUriMatches(text).forEach { match ->
                    add(TextLink.Uri(match.start, match.endExclusive, match.uri, match.text))
                }
            }
            if (includeProfiles) {
                extractNpubReferences(text).forEach { reference ->
                    add(TextLink.Profile(reference.start, reference.endExclusive, reference.pubkey))
                }
            }
            if (includeHashtags) {
                hashtagTextRegex.findAll(text).forEach { match ->
                    val tag = match.value.removePrefix("#")
                    add(TextLink.Hashtag(match.range.first, match.range.last + 1, tag))
                }
            }
        }.sortedBy { it.start }
    }

    val emojiSegments = remember(text, emojiMap) {
        if (emojiMap.isEmpty()) return@remember emptyList()
        emojiCodeRegex.findAll(text)
            .mapNotNull { match ->
                val shortcode = match.groupValues[1]
                val imageUrl = emojiMap[shortcode] ?: return@mapNotNull null
                EmojiSegment(match.range.first, match.range.last + 1, shortcode, imageUrl)
            }
            .toList()
    }

    val unregisteredEmojiSegments = remember(text, emojiMap) {
        emojiCodeRegex.findAll(text)
            .filter { match -> match.groupValues[1] !in emojiMap }
            .map { match ->
                UnregisteredEmojiSegment(match.range.first, match.range.last + 1, match.groupValues[1])
            }
            .toList()
    }

    val profileLabelKey = links.asSequence()
        .filterIsInstance<TextLink.Profile>()
        .joinToString(separator = "|") { link ->
            profiles[link.pubkey]?.bestName?.takeIf { it.isNotBlank() }.orEmpty()
        }
    val annotated = remember(text, links, emojiSegments, unregisteredEmojiSegments, profileLabelKey, linkColor, onProfileClick, onHashtagClick) {
        buildAnnotatedString {
            val allSegments: List<Segment> = (
                links.map { Segment.Link(it) } +
                emojiSegments.map { Segment.Emoji(it) } +
                unregisteredEmojiSegments.map { Segment.UnregisteredEmoji(it) }
            ).sortedBy { it.start }
            var cursor = 0
            for (segment in allSegments) {
                if (segment.start < cursor) continue
                if (segment.start > cursor) {
                    append(text.substring(cursor, segment.start))
                }
                when (segment) {
                    is Segment.Link -> when (val link = segment.link) {
                        is TextLink.Uri -> {
                            pushLink(LinkAnnotation.Url(url = link.uri, styles = linkStyle))
                            append(link.text)
                            pop()
                        }
                        is TextLink.Profile -> {
                            val label = profiles[link.pubkey]?.bestName
                                ?.takeIf { it.isNotBlank() }
                                ?: link.pubkey.shortPubkey()
                            pushLink(
                                LinkAnnotation.Clickable(
                                    tag = "npub:${link.pubkey}",
                                    styles = linkStyle,
                                    linkInteractionListener = { onProfileClick?.invoke(link.pubkey) },
                                ),
                            )
                            append("@$label")
                            pop()
                        }
                        is TextLink.Hashtag -> {
                            pushLink(
                                LinkAnnotation.Clickable(
                                    tag = "hashtag:${link.tag}",
                                    styles = linkStyle,
                                    linkInteractionListener = { onHashtagClick?.invoke(link.tag) },
                                ),
                            )
                            append(text.substring(link.start, link.endExclusive))
                            pop()
                        }
                    }
                    is Segment.Emoji -> appendInlineContent(
                        id = ":${segment.data.shortcode}:",
                        alternateText = ":${segment.data.shortcode}:",
                    )
                    is Segment.UnregisteredEmoji -> appendInlineContent(
                        id = unregisteredEmojiInlineContentId(segment.data.shortcode),
                        alternateText = ":${segment.data.shortcode}:",
                    )
                }
                cursor = segment.end
            }
            if (cursor < text.length) {
                append(text.substring(cursor))
            }
        }
    }

    val inlineContent = remember(emojiSegments, unregisteredEmojiSegments, linkColor, style) {
        buildMap {
            emojiSegments.distinctBy { it.shortcode }.forEach { emoji ->
                put(
                    ":${emoji.shortcode}:",
                    InlineTextContent(
                        Placeholder(
                            width = 1.2f.em,
                            height = 1.2f.em,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                        ),
                    ) {
                        NetworkImage(
                            url = emoji.imageUrl,
                            contentDescription = ":${emoji.shortcode}:",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds(),
                        )
                    },
                )
            }
            unregisteredEmojiSegments.distinctBy { it.shortcode }.forEach { emoji ->
                val label = ":${emoji.shortcode}:"
                put(
                    unregisteredEmojiInlineContentId(emoji.shortcode),
                    InlineTextContent(
                        Placeholder(
                            width = (label.length * 0.58f).em,
                            height = 1.6f.em,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                        ),
                    ) {
                        Text(
                            text = label,
                            style = style.copy(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                            modifier = Modifier.clickable {
                                CustomEmojiStore.requestOpenSearch(emoji.shortcode)
                            },
                        )
                    },
                )
            }
        }
    }

    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        inlineContent = inlineContent,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { result -> onTextLayout?.invoke(result) },
    )
}

@Composable
fun ProfileNameText(
    profile: NostrProfile?,
    fallback: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    LinkedText(
        text = profile?.bestName ?: fallback,
        modifier = modifier,
        style = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style,
        color = color,
        customEmojis = profile?.customEmojis.orEmpty(),
        enableWebLinks = false,
        maxLines = maxLines,
        overflow = overflow,
    )
}

private data class EmojiSegment(
    val start: Int,
    val end: Int,
    val shortcode: String,
    val imageUrl: String,
)

private data class UnregisteredEmojiSegment(
    val start: Int,
    val end: Int,
    val shortcode: String,
)

private sealed class Segment {
    abstract val start: Int
    abstract val end: Int

    class Link(val link: TextLink) : Segment() {
        override val start get() = link.start
        override val end get() = link.endExclusive
    }
    class Emoji(val data: EmojiSegment) : Segment() {
        override val start get() = data.start
        override val end get() = data.end
    }
    class UnregisteredEmoji(val data: UnregisteredEmojiSegment) : Segment() {
        override val start get() = data.start
        override val end get() = data.end
    }
}

private sealed class TextLink(
    val start: Int,
    val endExclusive: Int,
) {
    class Uri(start: Int, endExclusive: Int, val uri: String, val text: String) : TextLink(start, endExclusive)
    class Profile(start: Int, endExclusive: Int, val pubkey: String) : TextLink(start, endExclusive)
    class Hashtag(start: Int, endExclusive: Int, val tag: String) : TextLink(start, endExclusive)
}

private fun String.shortPubkey(): String = take(8) + "…" + takeLast(8)

private fun unregisteredEmojiInlineContentId(shortcode: String): String = "unregistered-emoji:$shortcode"
