package com.nostr.torinos.article

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.encodeNevent
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Clock

@Serializable enum class ArticleDraftMode { Regular, MatometrStructured, MatometrMarkdown }
@Serializable data class MatometrReference(
    val eventId: String,
    val authorPubkey: String? = null,
    val relayHints: List<String> = emptyList(),
    val commentaryMarkdown: String = "",
) {
    val uri: String get() = "nostr:" + encodeNevent(eventId, relayHints.filter {
        it.startsWith("wss://") && it.encodeToByteArray().size <= 255
    }.distinct().take(2), authorPubkey)
}
@Serializable data class ArticleDraft(
    val schemaVersion: Int = 1,
    val draftId: String = articleLocalId(),
    val ownerPubkey: String,
    val articleIdentifier: String = "torinos-${articleLocalId()}",
    val relayDraftIdentifier: String? = null,
    val baseEventId: String? = null,
    val originalPublishedAt: Long? = null,
    val mode: ArticleDraftMode = ArticleDraftMode.Regular,
    val title: String = "",
    val summary: String = "",
    val bodyMarkdown: String = "",
    val coverImageUrl: String = "",
    val topicsInput: String = "",
    val matometrTagEnabled: Boolean = true,
    val references: List<MatometrReference> = emptyList(),
    val createdAt: Long = Clock.System.now().epochSeconds,
    val updatedAt: Long = createdAt,
    val revision: Long = 0,
) {
    val isMatometr get() = mode != ArticleDraftMode.Regular
    val hasMatometrTag get() = isMatometr && matometrTagEnabled
    val content get() = serializeArticleContent(this)
    val hasContent get() = title.isNotBlank() || summary.isNotBlank() || bodyMarkdown.isNotBlank() ||
        coverImageUrl.isNotBlank() || topicsInput.isNotBlank() || references.isNotEmpty() || baseEventId != null
}
@Serializable data class ArticlePublishAttempt(
    val id: String = articleLocalId(),
    val draft: ArticleDraft,
    val event: NostrEvent,
    val relays: Map<String, ArticleRelayStatus>,
    val supersededBy: String? = null,
    val draftCleanupError: String? = null,
) {
    val accepted get() = relays.values.any { it.state == ArticleRelayState.Accepted }
    val complete get() = relays.isNotEmpty() && relays.values.all { it.state == ArticleRelayState.Accepted }
}
@Serializable enum class ArticleRelayState { Pending, Accepted, Rejected, Unknown }
@Serializable data class ArticleRelayStatus(val state: ArticleRelayState, val reason: String = "")
@Serializable data class ArticleIdentity(val identifier: String, val publishedAt: Long, val maxCreatedAt: Long)
@Serializable internal data class ArticlePublishLease(val holder: String, val generation: Long, val expiresAt: Long)
fun articleLocalId(): String = "${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong().toULong().toString(16)}"

fun serializeArticleContent(draft: ArticleDraft): String {
    return serializeArticleWithLayout(draft).content
}

/** Ranges refer to the one copy of the published Markdown in the encrypted draft. */
@Serializable internal data class ArticleDraftTextSlice(
    val start: Int, val end: Int, val prefix: String = "", val suffix: String = "", val literal: String? = null,
) {
    fun restore(content: String): String {
        require(start in 0..content.length && end in start..content.length)
        require(prefix.all { it == '\n' || it == '\r' } && suffix.all { it == '\n' || it == '\r' })
        literal?.let { require(it.isBlank()); return it }
        return prefix + content.substring(start, end) + suffix
    }
}
@Serializable internal data class ArticleDraftContentLayout(
    val body: ArticleDraftTextSlice, val commentaries: List<ArticleDraftTextSlice> = emptyList(),
) {
    fun restore(draft: ArticleDraft, content: String): ArticleDraft {
        require(commentaries.size == if (draft.mode == ArticleDraftMode.MatometrStructured) draft.references.size else 0)
        return draft.copy(bodyMarkdown = body.restore(content), references = draft.references.mapIndexed { index, ref ->
            if (draft.mode == ArticleDraftMode.MatometrStructured) ref.copy(commentaryMarkdown = commentaries[index].restore(content)) else ref
        })
    }
}
internal data class ArticleSerializedContent(val content: String, val layout: ArticleDraftContentLayout)
internal fun serializeArticleWithLayout(draft: ArticleDraft): ArticleSerializedContent {
    if (draft.mode == ArticleDraftMode.Regular) return ArticleSerializedContent(draft.bodyMarkdown,
        ArticleDraftContentLayout(ArticleDraftTextSlice(0, draft.bodyMarkdown.length)))
    val content = StringBuilder()
    fun appendPart(value: String): Pair<Int, Int> {
        if (content.isNotEmpty()) content.append("\n\n")
        val start = content.length
        content.append(value)
        return start to content.length
    }
    fun appendMarkdown(raw: String): ArticleDraftTextSlice {
        val trimmed = raw.trim('\n', '\r')
        if (trimmed.isBlank()) return ArticleDraftTextSlice(0, 0, literal = raw)
        val (start, end) = appendPart(trimmed)
        return ArticleDraftTextSlice(start, end,
            prefix = raw.take(raw.length - raw.trimStart('\n', '\r').length),
            suffix = raw.takeLast(raw.length - raw.trimEnd('\n', '\r').length))
    }
    val body = appendMarkdown(draft.bodyMarkdown)
    val commentaries = if (draft.mode == ArticleDraftMode.MatometrStructured) draft.references.map {
        appendPart(it.uri)
        appendMarkdown(it.commentaryMarkdown)
    } else emptyList()
    return ArticleSerializedContent(content.toString(), ArticleDraftContentLayout(body, commentaries))
}

internal const val ARTICLE_CONTENT_MAX_LENGTH = 10_000

fun validateMatometr(draft: ArticleDraft): String? {
    if (!draft.isMatometr) return null
    if (draft.references.size > 50) return "まとめる投稿は50件までです"
    if (draft.references.any { !it.hasValidIds() }) return "引用の投稿IDが不正です"
    if (draft.mode == ArticleDraftMode.MatometrStructured) {
        if (draft.references.isEmpty()) return "まとめる投稿を1件以上追加してください"
        if (!ArticleMarkdownBoundaryValidator.isClosed(draft.bodyMarkdown)) return "導入文のコードブロックを閉じてください"
        draft.references.forEachIndexed { index, ref ->
            if (!ArticleMarkdownBoundaryValidator.isClosed(ref.commentaryMarkdown))
                return "引用${index + 1}のコードブロックを閉じてください"
        }
        val ids = parseArticleMarkdown(draft.content, emptyList()).sections.flatMap { it.quoteIds }
        val managedIds = draft.references.map { it.eventId }
        if (managedIds.any { id -> id !in ids }) return "引用が本文のコードに含まれています"
    } else {
        if (!ArticleMarkdownBoundaryValidator.isClosed(draft.bodyMarkdown)) return "本文のコードブロックを閉じてください"
        if (parseArticleMarkdown(draft.content, emptyList()).sections.none { it.quoteIds.isNotEmpty() })
            return "本文に有効な投稿参照を1件以上追加してください"
    }
    val count = parseArticleMarkdown(draft.content, emptyList()).sections.flatMap { it.quoteIds }.distinct().size
    if (count > 50) return "まとめる投稿は50件までです"
    return null
}

internal fun MatometrReference.hasValidIds(): Boolean = eventId.isArticleHexId() &&
    (authorPubkey == null || authorPubkey.isArticleHexId())
private fun String.isArticleHexId() = length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
