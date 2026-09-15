package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private val xPostUrlRegex = Regex(
    pattern = """^https?://(?:(?:www|mobile)\.)?(?:x\.com|twitter\.com)/[^/?#]+/status/(\d+)(?:[/?#].*)?$""",
    option = RegexOption.IGNORE_CASE,
)

internal fun extractXPostId(url: String): String? =
    xPostUrlRegex.matchEntire(url.trim())?.groupValues?.getOrNull(1)

internal fun xPostEmbedUrl(
    postId: String,
    darkTheme: Boolean,
): String = buildString {
    append("https://platform.twitter.com/embed/Tweet.html?id=")
    append(postId)
    append("&dnt=true&theme=")
    append(if (darkTheme) "dark" else "light")
}

@Composable
internal expect fun XPostEmbed(
    postId: String,
    sourceUrl: String,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
)
