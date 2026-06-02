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
    val displayTitle: String get() = meta.title?.takeIf { it.isNotBlank() }
        ?: markdownPreview(event.content).ifBlank { "無題の記事" }
    val displaySummary: String get() = meta.summary?.takeIf { it.isNotBlank() }
        ?: markdownPreview(event.content)
    val sortTime: Long get() = meta.publishedAt ?: event.createdAt
}

data class ArticleAuthorItem(
    val pubkey: String,
    val profile: NostrProfile?,
    val articleCount: Int,
    val latestArticle: ArticleItem,
)

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

fun List<ArticleItem>.toArticleAuthors(): List<ArticleAuthorItem> =
    groupBy { it.event.pubkey }
        .mapNotNull { (pubkey, items) ->
            val latest = items.maxWithOrNull(compareBy<ArticleItem> { it.sortTime }.thenBy { it.event.createdAt })
                ?: return@mapNotNull null
            ArticleAuthorItem(
                pubkey = pubkey,
                profile = latest.authorProfile,
                articleCount = items.size,
                latestArticle = latest,
            )
        }
        .sortedWith(
            compareByDescending<ArticleAuthorItem> { it.latestArticle.sortTime }
                .thenByDescending { it.latestArticle.event.createdAt },
        )

fun NostrEvent.articleIdentifier(): String? = toArticleMeta()?.identifier

fun markdownPreview(content: String, maxLength: Int = ArticlePreviewMaxLength): String =
    content
        .lineSequence()
        .map { line ->
            line.trim()
                .removePrefix("#")
                .removePrefix("#")
                .removePrefix("#")
                .removePrefix(">")
                .trim()
        }
        .filter { it.isNotBlank() }
        .filterNot { it.startsWith("```") }
        .joinToString(" ")
        .replace(Regex("""!\[([^]]*)]\([^)]+\)"""), "$1")
        .replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1")
        .take(maxLength)

private fun NostrEvent.tagValue(name: String): String? =
    tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)
