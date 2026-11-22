package com.nostr.torinos.chat

/**
 * チャット一覧の1行に出す、最新の発言の要約。Markdown の記号は外し、画像・動画は「📷 画像」などにする。
 * 文章があれば最初の意味のある行を出し、添付があれば頭に印を付ける。何も無ければ null。
 */
fun chatListPreview(body: String): String? {
    val blocks = parseMarkdownLite(body)
    val media = blocks.filterIsInstance<MdBlock.Image>()
    val videos = media.count { isVideoUrl(it.url) }
    val images = media.size - videos
    val text = blocks.asSequence()
        .mapNotNull { block ->
            when (block) {
                is MdBlock.Paragraph -> block.inlines.plainText()
                is MdBlock.Heading -> block.inlines.plainText()
                is MdBlock.Quote -> block.inlines.plainText()
                is MdBlock.CodeBlock -> block.text.lineSequence().firstOrNull { it.isNotBlank() }
                is MdBlock.Image -> null
            }
        }
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .firstOrNull { it.isNotEmpty() }
    val mark = when {
        videos > 0 -> "🎬"
        images > 0 -> "📷"
        else -> null
    }
    return when {
        text != null && mark != null -> "$mark $text"
        text != null -> text
        videos > 0 && images == 0 -> if (videos == 1) "🎬 動画" else "🎬 動画 ${videos}本"
        media.isNotEmpty() -> if (media.size == 1) "📷 画像" else "📷 画像 ${media.size}枚"
        else -> null
    }
}

internal fun List<MdInline>.plainText(): String = joinToString("") { inline ->
    when (inline) {
        is MdInline.Text -> inline.text
        is MdInline.Bold -> inline.text
        is MdInline.Italic -> inline.text
        is MdInline.Code -> inline.text
        is MdInline.Link -> inline.text
        is MdInline.Spoiler -> "(ネタバレ)"
    }
}

private fun isVideoUrl(url: String): Boolean =
    url.substringBefore('?').substringBefore('#').lowercase().let { it.endsWith(".mp4") || it.endsWith(".mov") || it.endsWith(".webm") }
