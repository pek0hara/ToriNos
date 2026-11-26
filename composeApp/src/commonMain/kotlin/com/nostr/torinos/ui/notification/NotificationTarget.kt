package com.nostr.torinos.ui.notification

import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.channelRootId
import com.nostr.torinos.model.extractNostrAddressReferences
import com.nostr.torinos.model.extractNostrEventReferences
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.toArticleMeta
import com.nostr.torinos.network.isFullEventId
import kotlinx.serialization.json.Json

sealed interface TargetReference {
    data class EventId(val id: String) : TargetReference
    data object AddressOnly : TargetReference
    data object Invalid : TargetReference
    data object None : TargetReference
}

data class ResolvedTargetReference(val reference: TargetReference, val embedded: NostrEvent? = null)

/** Embedded signature verification can be expensive: call off the UI dispatcher. */
fun resolveNotificationTarget(
    event: NostrEvent?,
    legacyId: String? = null,
    validateEmbedded: (NostrEvent) -> Boolean = ::isValidEvent,
): ResolvedTargetReference {
    if (event == null) return ResolvedTargetReference(when {
        legacyId == null -> TargetReference.None
        isFullEventId(legacyId) -> TargetReference.EventId(legacyId)
        else -> TargetReference.Invalid
    })
    if (isQuoteNote(event)) {
        // 引用リポストは引用先が対象。記事だけを引用しているならアドレスしか分からない。
        val quoted = quotedNoteIds(event).firstOrNull()
        return ResolvedTargetReference(quoted?.let { TargetReference.EventId(it) } ?: TargetReference.AddressOnly)
    }
    val eTag = event.tags.lastOrNull { it.firstOrNull() == "e" }
    val eventReferenceTag = eTag ?: event.tags.firstOrNull {
        event.kind == COMMENT_EVENT_KIND && it.firstOrNull() == "E"
    }
    val id = if (event.kind == 1 || event.kind == COMMENT_EVENT_KIND) event.replyTargetId() else eTag?.getOrNull(1)
    val embedded = if (event.kind in listOf(6, 16) && event.content.isNotBlank()) {
        runCatching { Json.decodeFromString<NostrEvent>(event.content) }.getOrNull()
            ?.takeIf { (id == null || it.id == id) && validateEmbedded(it) }
    } else null
    val reference = when {
        eventReferenceTag != null -> if (id != null && isFullEventId(id)) TargetReference.EventId(id) else TargetReference.Invalid
        embedded != null -> TargetReference.EventId(embedded.id)
        event.tags.any { it.firstOrNull() == "a" } -> TargetReference.AddressOnly
        event.kind in listOf(6, 16) && event.content.isNotBlank() -> TargetReference.Invalid
        else -> TargetReference.None
    }
    return ResolvedTargetReference(reference, embedded.takeIf { reference is TargetReference.EventId })
}

/**
 * 返信先（`mention` の印のない `e` タグ）がなく、投稿や記事を引用している kind 1。
 * 返信しながら引用しているものは返信として扱う。
 */
fun isQuoteNote(event: NostrEvent): Boolean {
    if (event.kind != 1) return false
    if (event.tags.any { it.firstOrNull() == "e" && it.getOrNull(3) != "mention" }) return false
    return quotedNoteIds(event).isNotEmpty() || quotesAddress(event)
}

fun notificationTypeForNote(event: NostrEvent): NotificationType =
    if (isQuoteNote(event)) NotificationType.Quote else NotificationType.Reply

/** 引用している投稿。`q` タグ、`mention` の `e` タグ、本文の nevent / note の順。 */
private fun quotedNoteIds(event: NostrEvent): List<String> = (
    event.tags.filter { it.firstOrNull() == "q" || (it.firstOrNull() == "e" && it.getOrNull(3) == "mention") }
        .mapNotNull { it.getOrNull(1) } +
        extractNostrEventReferences(event.content).map { it.eventId }
    ).filter(::isFullEventId).distinct()

private fun quotesAddress(event: NostrEvent): Boolean =
    event.tags.any { it.firstOrNull() == "q" && it.getOrNull(1)?.contains(':') == true } ||
        extractNostrAddressReferences(event.content).isNotEmpty()

sealed interface NotificationTargetDestination {
    data class Thread(val eventId: String) : NotificationTargetDestination
    data class ChannelThread(val eventId: String, val channelId: String) : NotificationTargetDestination
    data class Article(val pubkey: String, val identifier: String) : NotificationTargetDestination
}

fun notificationTargetDestination(event: NostrEvent): NotificationTargetDestination? = when (event.kind) {
    1, COMMENT_EVENT_KIND -> NotificationTargetDestination.Thread(event.id)
    42 -> event.channelRootId()?.takeIf(::isFullEventId)?.let {
        NotificationTargetDestination.ChannelThread(event.id, it)
    }
    30023 -> event.toArticleMeta()?.let { NotificationTargetDestination.Article(event.pubkey, it.identifier) }
    else -> null
}

/** Unknown kinds deliberately never render content (it may be JSON or ciphertext). */
fun notificationTargetBody(event: NostrEvent): String = when (event.kind) {
    1, 42, COMMENT_EVENT_KIND -> event.content
    30023 -> event.toArticleMeta()?.let { it.title ?: it.summary ?: "長文記事" } ?: "Kind 30023"
    else -> "Kind ${event.kind} のイベント（専用画面は未対応）"
}
