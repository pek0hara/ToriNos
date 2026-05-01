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

/** テキスト内のURLをクリッカブルリンクにして表示する */
@Composable
fun LinkedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = buildAnnotatedString {
        var cursor = 0
        var searchStart = 0
        for (url in extractWebUrls(text)) {
            val start = text.indexOf(url, searchStart)
            if (start < 0) continue
            if (start > cursor) {
                append(text.substring(cursor, start))
            }
            pushLink(
                LinkAnnotation.Url(
                    url = url,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            )
            append(url)
            pop()
            cursor = start + url.length
            searchStart = cursor
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
    Text(text = annotated, modifier = modifier, style = style, color = color)
}
