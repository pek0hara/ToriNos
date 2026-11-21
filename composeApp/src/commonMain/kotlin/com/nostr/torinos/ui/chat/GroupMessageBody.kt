package com.nostr.torinos.ui.chat

import com.nostr.torinos.chat.displayText
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
/** 長い本文を畳む基準（文字数と行数。[com.nostr.torinos.chat.collapseMarkdown]）。 */
internal data class BodyCollapse(val chars: Int, val lines: Int) {
    companion object {
        /** フィードの長い投稿と同じ。スレッドの画面など、読むための画面。 */
        val Feed = BodyCollapse(com.nostr.torinos.ui.components.CollapsedTextCharacterLimit, com.nostr.torinos.ui.components.CollapsedTextMaxVisibleLines)
        /** チャンネルの画面の発言。流れを追いやすいよう短めに畳む。 */
        val Channel = BodyCollapse(chars = 80, lines = 5)
        /** 投稿の下に出す最新の返信。押せばスレッドが開くので、さらに短く。 */
        val ReplyPreview = BodyCollapse(chars = 50, lines = 3)
    }
}

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
    /** 長い本文をフィードの長い投稿と同じ基準で畳み、「もっと見る」で開く。 */
    collapsible: Boolean = false,
    /** [collapsible] のときの畳む基準。 */
    collapse: BodyCollapse = BodyCollapse.Feed,
    /** メンションできる名前（表示名 → その人）。本文の `@表示名` をリンクにする（[com.nostr.torinos.chat.splitMentions]）。 */
    mentionNames: Map<String, Set<String>> = emptyMap(),
    /** メンションを押した（その人の pubkey）。null ならリンクにせず色だけ付ける。 */
    onMentionClick: ((String) -> Unit)? = null,
    /** 本文の `nostr:npub1…` に出す名前（pubkey → 名前）。分からなければ短くした npub を出す。 */
    profileName: (String) -> String? = { null },
) {
    val mentions = MentionLinks(mentionNames, onMentionClick, profileName)
    val allBlocks = remember(text) { parseMarkdownLite(text) }
    var expanded by remember(text) { mutableStateOf(false) }
    val collapsed = remember(allBlocks, emojis.keys, collapsible, collapse) {
        if (!collapsible) null
        else com.nostr.torinos.chat.collapseMarkdown(
            allBlocks,
            maxChars = collapse.chars,
            maxLines = collapse.lines,
            emojiShortcodes = emojis.keys,
        ).takeIf { it.truncated }
    }
    val blocks = if (collapsed != null && !expanded) collapsed.blocks else allBlocks
    val emojiImages = rememberEmojiImages(emojis, imageAuth, imagesUnlocked)
    // 押した画像を全画面で開く。読み込めた画像（とその認証ヘッダー）だけをページにする。
    val imageUrls = remember(allBlocks) { allBlocks.filterIsInstance<MdBlock.Image>().map { it.url }.distinct() }
    val loadedHeaders = remember(text) { androidx.compose.runtime.mutableStateMapOf<String, Map<String, String>>() }
    var expandedUrl by remember(text) { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    // naddr のグループへのリンクは、フィードの本文と同じくチャットタブの参加シートで開く。
    val groupLinkHandler = com.nostr.torinos.ui.components.LocalGroupLinkHandler.current
    val openLink: (String) -> Unit = { url ->
        when {
            url.startsWith("buzz://", ignoreCase = true) -> onBuzzLink(url)
            url.startsWith("nostr:", ignoreCase = true) -> {
                val pubkey = com.nostr.torinos.chat.npubOfLink(url)
                when {
                    pubkey != null -> onMentionClick?.invoke(pubkey)
                    com.nostr.torinos.model.isGroupLinkUri(url) -> groupLinkHandler?.invoke(url)
                }
            }
            com.nostr.torinos.chat.isSafeExternalLink(url) -> runCatching { uriHandler.openUri(url) }
            // javascript: などは開かない。
            else -> Unit
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> InlineText(block.inlines, openLink, emojiImages, mentions)
                is MdBlock.Heading -> InlineText(
                    block.inlines,
                    openLink,
                    emojiImages,
                    mentions,
                    style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                )
                is MdBlock.Quote -> Row {
                    Box(Modifier.width(3.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Spacer(Modifier.width(8.dp))
                    InlineText(block.inlines, openLink, emojiImages, mentions, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        if (collapsed != null) {
            com.nostr.torinos.ui.components.InlineTextAction(
                label = if (expanded) "閉じる" else "もっと見る",
                onClick = { expanded = !expanded },
            )
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

/**
 * Buzz のメッセージのメンションの名前（表示名 → その人）。Buzz と同じく、編集したメッセージはタグと本文が合わないのでリンクにしない。
 */
@Composable
internal fun rememberMentionNames(
    message: com.nostr.torinos.chat.GroupMessage,
    profiles: Map<String, com.nostr.torinos.model.NostrProfile>,
): Map<String, Set<String>> {
    val targets = remember(message.event, message.edited) {
        if (message.edited) emptyList() else com.nostr.torinos.chat.buzzMentionTargets(message.event)
    }
    if (targets.isEmpty()) return emptyMap()
    val names = targets.map { profiles[it]?.bestName }
    return remember(targets, names) { com.nostr.torinos.chat.mentionNameTable(targets) { profiles[it]?.bestName } }
}

/** 本文の `@表示名` のリンク。 */
private class MentionLinks(
    val names: Map<String, Set<String>>,
    val onClick: ((String) -> Unit)?,
    val profileName: (String) -> String? = { null },
)

@Composable
private fun InlineText(
    inlines: List<MdInline>,
    onLink: (String) -> Unit,
    emojis: Map<String, EmojiImage>,
    mentions: MentionLinks,
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
    // `@表示名` は名前の色にして、押すとその人のプロフィールを開く。
    fun AnnotatedString.Builder.appendWithMentions(text: String) {
        com.nostr.torinos.chat.splitMentions(text, mentions.names).forEach { part ->
            val pubkey = part.pubkey
            if (pubkey == null) {
                appendWithEmoji(part.text)
                return@forEach
            }
            val mentionStyle = SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)
            val onClick = mentions.onClick
            if (onClick == null) {
                withStyle(mentionStyle) { append(part.text) }
            } else {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "mention:$pubkey",
                        styles = TextLinkStyles(mentionStyle),
                        linkInteractionListener = { onClick(pubkey) },
                    ),
                ) { append(part.text) }
            }
        }
    }
    val annotated: AnnotatedString = buildAnnotatedString {
        linked.forEach { inline ->
            when (inline) {
                is MdInline.Text -> appendWithMentions(inline.text)
                is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendWithMentions(inline.text) }
                is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendWithMentions(inline.text) }
                is MdInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(inline.text) }
                is MdInline.Link -> {
                    val npubKey = com.nostr.torinos.chat.npubOfLink(inline.url)
                    if (npubKey != null) {
                        // むき出しの `nostr:npub1…` はフィードと同じ `@名前` にする。押すとその人のプロフィールを開く。
                        // 見た目は同じ本文の `@表示名` のメンションに合わせる。
                        val bare = inline.displayText() != inline.text
                        val label = if (bare) com.nostr.torinos.model.profileLinkLabel(npubKey, mentions.profileName(npubKey)) else inline.text
                        val mentionStyle = SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)
                        val onClick = mentions.onClick
                        if (onClick == null) {
                            withStyle(mentionStyle) { append(label) }
                        } else {
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "mention:$npubKey",
                                    styles = TextLinkStyles(mentionStyle),
                                    linkInteractionListener = { onClick(npubKey) },
                                ),
                            ) { append(label) }
                        }
                    } else {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = inline.url,
                                styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                                linkInteractionListener = { onLink(inline.url) },
                            ),
                        ) { append(inline.displayText()) }
                    }
                }
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
