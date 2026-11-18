package com.nostr.torinos.ui.chat

import com.nostr.torinos.chat.withAutolinks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import com.nostr.torinos.emoji.CustomEmojiCodeRegex
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nostr.torinos.chat.MdBlock
import com.nostr.torinos.chat.MdInline
import com.nostr.torinos.chat.parseMarkdownLite
import com.nostr.torinos.ui.components.NetworkImage

/**
 * Buzz のメッセージ本文（Markdown）。`buzz://` のリンクは [onBuzzLink] へ、それ以外のリンクはブラウザで開く。
 * 画像は [imageAuth] で認証を決めてから読む。
 */
@Composable
internal fun MarkdownMessageBody(
    text: String,
    imageAuth: suspend (String) -> ImageAuth,
    onUnlockImages: () -> Unit,
    onBuzzLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 画像の署名を許可した。変わったら画像の認証を決め直す。 */
    imagesUnlocked: Boolean = false,
    /** カスタム絵文字（小文字の shortcode → URL）。 */
    emojis: Map<String, String> = emptyMap(),
) {
    val blocks = remember(text) { parseMarkdownLite(text) }
    val emojiImages = rememberEmojiImages(emojis, imageAuth, imagesUnlocked)
    // 押した画像を全画面で開く。読み込めた画像（とその認証ヘッダー）だけをページにする。
    val imageUrls = remember(blocks) { blocks.filterIsInstance<MdBlock.Image>().map { it.url }.distinct() }
    val loadedHeaders = remember(text) { androidx.compose.runtime.mutableStateMapOf<String, Map<String, String>>() }
    var expandedUrl by remember(text) { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = { url ->
        when {
            url.startsWith("buzz://", ignoreCase = true) -> onBuzzLink(url)
            com.nostr.torinos.chat.isSafeExternalLink(url) -> runCatching { uriHandler.openUri(url) }
            // javascript: などは開かない。
            else -> Unit
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> InlineText(block.inlines, openLink, emojiImages)
                is MdBlock.Heading -> InlineText(
                    block.inlines,
                    openLink,
                    emojiImages,
                    style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                )
                is MdBlock.Quote -> Row {
                    Box(Modifier.width(3.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Spacer(Modifier.width(8.dp))
                    InlineText(block.inlines, openLink, emojiImages, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is MdBlock.CodeBlock -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )
                is MdBlock.Image -> AuthImage(
                    block.url, block.alt, imageAuth, onUnlockImages, imagesUnlocked,
                    onLoaded = { headers -> loadedHeaders[block.url] = headers },
                    onClick = { expandedUrl = block.url },
                )
            }
        }
    }
    expandedUrl?.let { url ->
        val pages = imageUrls.filter { it in loadedHeaders }
        com.nostr.torinos.ui.components.ExpandedImageDialog(
            imageUrls = pages,
            initialIndex = pages.indexOf(url).coerceAtLeast(0),
            onDismiss = { expandedUrl = null },
            headers = loadedHeaders.toMap(),
        )
    }
}

@Composable
private fun InlineText(
    inlines: List<MdInline>,
    onLink: (String) -> Unit,
    emojis: Map<String, EmojiImage>,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
) {
    var revealed by remember(inlines) { mutableStateOf(false) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val spoilerColor = MaterialTheme.colorScheme.onSurfaceVariant
    // むき出しの URL も押せるようにする。
    val linked = remember(inlines) { inlines.withAutolinks() }
    // `:shortcode:` は画像を読めるものだけ置き換える（読めないものは文字のまま）。リンクやコードの中は置き換えない。
    fun AnnotatedString.Builder.appendWithEmoji(text: String) {
        var cursor = 0
        CustomEmojiCodeRegex.findAll(text).forEach { match ->
            val key = match.groupValues[1].lowercase()
            if (key !in emojis) return@forEach
            append(text.substring(cursor, match.range.first))
            appendInlineContent(emojiInlineId(key), match.value)
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    }
    val annotated: AnnotatedString = buildAnnotatedString {
        linked.forEach { inline ->
            when (inline) {
                is MdInline.Text -> appendWithEmoji(inline.text)
                is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendWithEmoji(inline.text) }
                is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendWithEmoji(inline.text) }
                is MdInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(inline.text) }
                is MdInline.Link -> withLink(
                    LinkAnnotation.Clickable(
                        tag = inline.url,
                        styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { onLink(inline.url) },
                    ),
                ) { append(inline.text) }
                is MdInline.Spoiler -> if (revealed) {
                    append(inline.text)
                } else {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "spoiler",
                            styles = TextLinkStyles(SpanStyle(color = spoilerColor, background = spoilerColor)),
                            linkInteractionListener = { revealed = true },
                        ),
                    ) { append(inline.text) }
                }
            }
        }
    }
    val inlineContent = remember(emojis) {
        emojis.mapKeys { emojiInlineId(it.key) }.mapValues { (_, image) ->
            InlineTextContent(Placeholder(1.2f.em, 1.2f.em, PlaceholderVerticalAlign.Center)) { alt ->
                NetworkImage(
                    url = image.url,
                    contentDescription = alt,
                    contentScale = ContentScale.Fit,
                    maxDecodeSizePx = 96,
                    headers = image.headers,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    Text(annotated, style = style, color = color, inlineContent = inlineContent)
}

private fun emojiInlineId(shortcode: String) = "emoji:$shortcode"

/** 読み込める状態になったカスタム絵文字の画像。 */
internal data class EmojiImage(val url: String, val headers: Map<String, String>)

/**
 * カスタム絵文字の画像を、認証を決めてから使えるようにする（Buzz の `/media/` は署名が要る）。
 * 許可待ちや失敗のものは入れない。その絵文字は `:shortcode:` の文字のまま出る。
 */
@Composable
internal fun rememberEmojiImages(
    emojis: Map<String, String>,
    imageAuth: suspend (String) -> ImageAuth,
    imagesUnlocked: Boolean,
): Map<String, EmojiImage> {
    var images by remember(emojis) { mutableStateOf<Map<String, EmojiImage>>(emptyMap()) }
    LaunchedEffect(emojis, imagesUnlocked) {
        if (emojis.isEmpty()) return@LaunchedEffect
        val headersByUrl = emojis.values.distinct().mapNotNull { url ->
            when (val auth = imageAuth(url)) {
                ImageAuth.NotNeeded -> url to emptyMap<String, String>()
                is ImageAuth.Header -> url to mapOf("Authorization" to auth.authorization)
                ImageAuth.NeedsConsent, ImageAuth.Failed -> null
            }
        }.toMap()
        images = emojis.mapNotNull { (code, url) -> headersByUrl[url]?.let { code to EmojiImage(url, it) } }.toMap()
    }
    return images
}

/** 認証が要る画像（Buzz）は、署名してヘッダーを付けて読む。外部署名器ではタップで許可してから。 */
@Composable
private fun AuthImage(
    url: String,
    alt: String,
    imageAuth: suspend (String) -> ImageAuth,
    onUnlock: () -> Unit,
    unlocked: Boolean,
    onLoaded: (Map<String, String>) -> Unit = {},
    onClick: () -> Unit = {},
) {
    var auth by remember(url) { mutableStateOf<ImageAuth?>(null) }
    LaunchedEffect(url, unlocked) { auth = imageAuth(url) }
    val shape = RoundedCornerShape(8.dp)
    when (val current = auth) {
        null -> Box(Modifier.width(160.dp).height(120.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
        ImageAuth.NeedsConsent -> Box(
            modifier = Modifier
                .width(200.dp)
                .height(120.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onUnlock),
            contentAlignment = Alignment.Center,
        ) {
            Text("タップして画像を読み込む", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ImageAuth.Failed -> Text("画像を読み込めませんでした（$alt）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else -> {
            val headers = (current as? ImageAuth.Header)?.let { mapOf("Authorization" to it.authorization) } ?: emptyMap()
            LaunchedEffect(url, headers) { onLoaded(headers) }
            NetworkImage(
                url = url,
                contentDescription = alt,
                contentScale = ContentScale.Fit,
                maxDecodeSizePx = 1200,
                headers = headers,
                modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 320.dp).clip(shape).clickable(onClick = onClick),
            )
        }
    }
}
