package com.nostr.torinos.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.nostr.torinos.model.decodeNpub

private val npubTextRegex = Regex(
    pattern = """\bnpub1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+""",
    option = RegexOption.IGNORE_CASE,
)

/** テキスト内のURLをクリッカブルリンクにして表示する */
@Composable
fun LinkedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    onProfileClick: ((pubkey: String) -> Unit)? = null,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline,
        ),
    )
    val links = buildList {
        extractWebUrlMatches(text).forEach { match ->
            add(TextLink.Web(match.start, match.endExclusive, match.url))
        }
        if (onProfileClick != null) {
            npubTextRegex.findAll(text).forEach { match ->
                decodeNpub(match.value)?.let { pubkey ->
                    add(TextLink.Profile(match.range.first, match.range.last + 1, pubkey))
                }
            }
        }
    }.sortedBy { it.start }
    val annotated = buildAnnotatedString {
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
                    pushLink(
                        LinkAnnotation.Clickable(
                            tag = "npub:${link.pubkey}",
                            styles = linkStyle,
                            linkInteractionListener = { onProfileClick?.invoke(link.pubkey) },
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
    Text(text = annotated, modifier = modifier, style = style, color = color)
}

private sealed class TextLink(
    val start: Int,
    val endExclusive: Int,
) {
    class Web(start: Int, endExclusive: Int, val url: String) : TextLink(start, endExclusive)
    class Profile(start: Int, endExclusive: Int, val pubkey: String) : TextLink(start, endExclusive)
}
