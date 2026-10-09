package com.nostr.torinos.article

import com.nostr.torinos.model.NostrEventReference
import com.nostr.torinos.model.extractNostrEventReferences

internal const val MATOMETR_MAX_REFERENCES = 50

/** Post references found in pasted text, and how many other Nostr references (articles, profiles) were skipped. */
internal data class PastedEventReferences(val references: List<NostrEventReference>, val unsupported: Int)

private val otherNostrReference = Regex("""\b(?:nostr:)?(naddr|npub|nprofile)1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+""", RegexOption.IGNORE_CASE)
private val hexEventId = Regex("""[0-9a-fA-F]{64}""")

/**
 * Accepts note/nevent with or without `nostr:`, including inside a web client's URL. A bare hex ID is only
 * accepted as the whole input, so a hash inside other text is not mistaken for a post.
 */
internal fun parsePastedEventReferences(text: String): PastedEventReferences {
    val trimmed = text.trim()
    if (hexEventId.matches(trimmed)) return PastedEventReferences(listOf(NostrEventReference(trimmed.lowercase())), 0)
    val references = extractNostrEventReferences(text).map { it.copy(eventId = it.eventId.lowercase(), authorPubkey = it.authorPubkey?.lowercase()) }
        .distinctBy { it.eventId }
    return PastedEventReferences(references, otherNostrReference.findAll(text).count())
}

internal fun NostrEventReference.toMatometrReference() = MatometrReference(eventId, authorPubkey, relayUrls)

/**
 * Adds posts to an existing draft: cards go to the end of the list, free Markdown gets each new reference as its own
 * paragraph at the end of the body. Nothing is added when the result would break a limit.
 */
internal fun appendMatometrReferences(draft: ArticleDraft, references: List<MatometrReference>): Result<ArticleDraft> = runCatching {
    val next = if (draft.mode == ArticleDraftMode.MatometrStructured) {
        draft.copy(references = (draft.references + references).distinctBy { it.eventId })
    } else {
        check(ArticleMarkdownBoundaryValidator.isClosed(draft.bodyMarkdown)) { "コードブロックを閉じてください" }
        val ids = parseArticleMarkdown(draft.bodyMarkdown, emptyList()).sections.flatMap { it.quoteIds }.toSet()
        val extra = references.filterNot { it.eventId in ids }.distinctBy { it.eventId }.joinToString("\n\n") { it.uri }
        draft.copy(bodyMarkdown = listOf(draft.bodyMarkdown, extra).filter { it.isNotBlank() }.joinToString("\n\n"))
    }
    val before = quotedPostCount(draft)
    val after = quotedPostCount(next)
    check(next.references.size <= MATOMETR_MAX_REFERENCES && after <= MATOMETR_MAX_REFERENCES) {
        val left = (MATOMETR_MAX_REFERENCES - maxOf(before, draft.references.size)).coerceAtLeast(0)
        if (left == 0) "まとめる投稿は${MATOMETR_MAX_REFERENCES}件までです" else "あと${left}件まで追加できます"
    }
    check(next.content.length <= ARTICLE_CONTENT_MAX_LENGTH) { "追加すると本文が10,000文字を超えます" }
    if (next.isMatometr) validateMatometr(next)?.let { error(it) }
    next
}

private fun quotedPostCount(draft: ArticleDraft) =
    parseArticleMarkdown(draft.content, emptyList()).sections.flatMap { it.quoteIds }.distinct().size
