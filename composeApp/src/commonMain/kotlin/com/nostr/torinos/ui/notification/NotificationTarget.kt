package com.nostr.torinos.ui.notification

import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.channelRootId
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.toArticleMeta
import com.nostr.torinos.model.toLiveActivityMeta
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
    val eTag = event.tags.lastOrNull { it.firstOrNull() == "e" }
    val id = if (event.kind == 1) event.replyTargetId() else eTag?.getOrNull(1)
    val embedded = if (event.kind == 6 && event.content.isNotBlank()) {
        runCatching { Json.decodeFromString<NostrEvent>(event.content) }.getOrNull()
            ?.takeIf { (id == null || it.id == id) && validateEmbedded(it) }
    } else null
    val reference = when {
        eTag != null -> if (id != null && isFullEventId(id)) TargetReference.EventId(id) else TargetReference.Invalid
        embedded != null -> TargetReference.EventId(embedded.id)
        event.tags.any { it.firstOrNull() == "a" } -> TargetReference.AddressOnly
        event.kind == 6 && event.content.isNotBlank() -> TargetReference.Invalid
        else -> TargetReference.None
    }
    return ResolvedTargetReference(reference, embedded.takeIf { reference is TargetReference.EventId })
}

sealed interface NotificationTargetDestination {
    data class Thread(val eventId: String) : NotificationTargetDestination
    data class ChannelThread(val eventId: String, val channelId: String) : NotificationTargetDestination
    data class Article(val pubkey: String, val identifier: String) : NotificationTargetDestination
    data class Live(val pubkey: String, val identifier: String) : NotificationTargetDestination
}

fun notificationTargetDestination(event: NostrEvent): NotificationTargetDestination? = when (event.kind) {
    1 -> NotificationTargetDestination.Thread(event.id)
    42 -> event.channelRootId()?.takeIf(::isFullEventId)?.let {
        NotificationTargetDestination.ChannelThread(event.id, it)
    }
    30023 -> event.toArticleMeta()?.let { NotificationTargetDestination.Article(event.pubkey, it.identifier) }
    30311 -> event.toLiveActivityMeta()?.let { NotificationTargetDestination.Live(event.pubkey, it.identifier) }
    else -> null
}

/** Unknown kinds deliberately never render content (it may be JSON or ciphertext). */
fun notificationTargetBody(event: NostrEvent): String = when (event.kind) {
    1, 42 -> event.content
    30023 -> event.toArticleMeta()?.let { it.title ?: it.summary ?: "長文記事" } ?: "Kind 30023"
    30311 -> event.toLiveActivityMeta()?.let { it.title ?: "ライブ" } ?: "Kind 30311"
    else -> "Kind ${event.kind} のイベント（専用画面は未対応）"
}
