package com.nostr.torinos.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences

private val hashtagTextRegex = Regex("""(?<![\p{L}\p{N}_])#[\p{L}\p{N}_]+""")

/** テキスト内のURLをクリッカブルリンクにして表示する */
@Composable
fun LinkedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    onProfileClick: ((pubkey: String) -> Unit)? = null,
    profiles: Map<String, NostrProfile> = emptyMap(),
    onHashtagClick: ((tag: String) -> Unit)? = null,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline,
        ),
    )
    val includeProfiles = onProfileClick != null
    val includeHashtags = onHashtagClick != null
    val links = remember(text, includeProfiles, includeHashtags) {
        buildList {
            extractWebUrlMatches(text).forEach { match ->
                add(TextLink.Web(match.start, match.endExclusive, match.url))
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
    val profileLabelKey = links.asSequence()
        .filterIsInstance<TextLink.Profile>()
        .joinToString(separator = "|") { link ->
            profiles[link.pubkey]?.bestName?.takeIf { it.isNotBlank() }.orEmpty()
        }
    val annotated = remember(text, links, profileLabelKey, linkColor, onProfileClick, onHashtagClick) {
        buildAnnotatedString {
            var cursor = 0
            for (link in links) {
                if (link.start < cursor) continue
                if (link.start > cursor) {
                    append(text.substring(cursor, link.start))
                }
                when (link) {
                    is TextLink.Web -> {
                        pushLink(LinkAnnotation.Url(url = link.url, styles = linkStyle))
                        append(link.url)
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
                cursor = link.endExclusive
            }
            if (cursor < text.length) {
                append(text.substring(cursor))
            }
        }
    }
    Text(text = annotated, modifier = modifier, style = style, color = color)
}

private sealed class TextLink(
    val start: Int,
    val endExclusive: Int,
) {
    class Web(start: Int, endExclusive: Int, val url: String) : TextLink(start, endExclusive)
    class Profile(start: Int, endExclusive: Int, val pubkey: String) : TextLink(start, endExclusive)
    class Hashtag(start: Int, endExclusive: Int, val tag: String) : TextLink(start, endExclusive)
}

private fun String.shortPubkey(): String = take(8) + "…" + takeLast(8)
