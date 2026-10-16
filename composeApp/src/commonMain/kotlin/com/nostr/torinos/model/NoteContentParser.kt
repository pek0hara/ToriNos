package com.nostr.torinos.model

data class ParsedNoteContent(
    val textContent: String,
    val images: List<MediaMetadata>,
    val linkPreviewUrl: String?,
    /** 投稿カード内のプレイヤーで再生する動画・音声。本文での出現順。 */
    val playableMedia: List<MediaMetadata> = emptyList(),
    /** naddr で引用している記事。本文からは取り除き、記事カードで表示する。 */
    val articleQuotes: List<NostrAddressReference> = emptyList(),
) {
    val imageUrls: List<String> get() = images.map { it.url }
}

fun parseNoteContent(event: NostrEvent): ParsedNoteContent {
    val content = event.content
    val inlineMetadata = parseImetaTags(event.tags)
        .filter { metadata -> content.contains(metadata.url) && metadata.mediaKind != null }
    val nip94Metadata = parseNip94Event(event)?.takeIf { it.mediaKind != null }
    val metadataByUrl = (inlineMetadata + listOfNotNull(nip94Metadata)).associateBy { it.url }
    val metadataFor = { url: String -> metadataByUrl[url] ?: MediaMetadata(url = url) }
    val imageUrls = (extractImageUrls(content) + metadataByUrl.filterValues { it.isImage }.keys)
        .distinct()
        .filter { url -> metadataFor(url).isImage }
    val images = imageUrls.map(metadataFor)
    val contentWithoutQuotes = stripNostrEventUris(content)
    val webUrls = extractWebUrls(contentWithoutQuotes)
    val playableMedia = (webUrls + metadataByUrl.keys)
        .distinct()
        .map(metadataFor)
        .filter { it.isPlayable }
    val hiddenUrls = imageUrls + playableMedia.map { it.url }
    val linkPreviewUrl = webUrls
        .firstOrNull { it !in hiddenUrls && !isImageUrl(it) && metadataFor(it).mediaKind == null }
    val textContent = if (hiddenUrls.isNotEmpty()) {
        hiddenUrls.fold(stripImageUrls(contentWithoutQuotes)) { text, url -> text.replace(url, "") }.trim()
    } else {
        contentWithoutQuotes
    }
    return ParsedNoteContent(
        textContent = textContent,
        images = images,
        linkPreviewUrl = linkPreviewUrl,
        playableMedia = playableMedia,
        articleQuotes = extractArticleQuoteReferences(content),
    )
}

internal fun hasContentWarning(tags: List<List<String>>): Boolean =
    tags.any { it.firstOrNull() == "content-warning" }

