package com.nostr.torinos.model

private const val ArticlePreviewMaxLength = 160

const val NIP23_ARTICLE_KIND = 30023

data class ArticleMeta(
    val identifier: String,
    val title: String?,
    val summary: String?,
    val imageUrl: String?,
    val publishedAt: Long?,
    val topics: List<String>,
)

data class ArticleItem(
    val event: NostrEvent,
    val meta: ArticleMeta,
    val authorProfile: NostrProfile? = null,
) {
    val address: String get() = articleAddress(event.pubkey, meta.identifier)
    // Computed once per item: the list reads these on every recomposition and the body can be 10,000 characters.
    private val preview by lazy { markdownPreview(event.content) }
    val displayTitle: String get() = meta.title?.takeIf { it.isNotBlank() } ?: preview.ifBlank { "無題の記事" }
    val isMatometr: Boolean get() = meta.topics.any { it.equals(MATOMETR_TOPIC, ignoreCase = true) }
    val displaySummary: String by lazy {
        meta.summary?.takeIf { it.isNotBlank() } ?: preview.ifBlank {
            // A matometr of quotes only has no prose to preview; say what it holds instead of leaving a gap.
            val quoted = extractNostrEventReferences(event.content).size
            if (isMatometr && quoted > 0) "${quoted}件の投稿をまとめた記事" else ""
        }
    }
    val sortTime: Long get() = meta.publishedAt ?: event.createdAt
}

fun articleAddress(pubkey: String, identifier: String): String =
    "$NIP23_ARTICLE_KIND:$pubkey:$identifier"

fun NostrEvent.toArticleMeta(): ArticleMeta? {
    if (kind != NIP23_ARTICLE_KIND) return null
    val identifier = tagValue("d")?.takeIf { it.isNotBlank() } ?: return null
    return ArticleMeta(
        identifier = identifier,
        title = tagValue("title"),
        summary = tagValue("summary"),
        imageUrl = tagValue("image"),
        publishedAt = tagValue("published_at")?.toLongOrNull(),
        topics = tags.filter { it.firstOrNull() == "t" }.mapNotNull { it.getOrNull(1) }.filter { it.isNotBlank() },
    )
}

fun List<ArticleItem>.latestArticleVersions(): List<ArticleItem> =
    groupBy { it.address }
        .values
        .mapNotNull { versions -> versions.maxByOrNull { it.event.createdAt } }
        .sortedWith(compareByDescending<ArticleItem> { it.sortTime }.thenByDescending { it.event.createdAt })

fun NostrEvent.articleIdentifier(): String? = toArticleMeta()?.identifier

/**
 * 一覧用の本文プレビュー。記号やコード、投稿の参照(`nostr:nevent1…`)と、
 * まとめ記事の末尾に付く `#matometr` 行は読み手に意味がないので除く。
 */
fun markdownPreview(content: String, maxLength: Int = ArticlePreviewMaxLength): String {
    var inFence = false
    return content
        .lineSequence()
        .mapNotNull { raw ->
            val line = raw.trim()
            if (line.startsWith("```") || line.startsWith("~~~")) { inFence = !inFence; return@mapNotNull null }
            if (inFence || line.equals("#$MATOMETR_TOPIC", ignoreCase = true)) return@mapNotNull null
            line.replace(previewBlockMarker, "")
                .replace(previewImage, "$1")
                .replace(previewLink, "$1")
                .let(::stripNostrEventUris)
                .replace(previewEmphasis, "")
                .trim()
        }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .take(maxLength)
}

const val MATOMETR_TOPIC = "matometr"
private val previewBlockMarker = Regex("""^(#{1,6}\s+|>\s?|[-*+]\s+)""")
private val previewImage = Regex("""!\[([^]]*)]\([^)]+\)""")
private val previewLink = Regex("""\[([^]]+)]\([^)]+\)""")
private val previewEmphasis = Regex("""\*\*|__|~~|`""")

private fun NostrEvent.tagValue(name: String): String? =
    tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)
