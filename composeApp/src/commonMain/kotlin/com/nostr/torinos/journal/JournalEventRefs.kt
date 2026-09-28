package com.nostr.torinos.journal

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.network.isFullEventId
import kotlinx.serialization.json.Json

/** リポスト・いいねの対象。最後の`e`タグ、なければリポストに埋め込まれたイベント。 */
internal fun NostrEvent.activityTargetId(): String? =
    tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
        ?: embeddedRepostTarget()?.id

internal fun NostrEvent.embeddedRepostTarget(): NostrEvent? {
    if (kind != 6 || content.isBlank()) return null
    return runCatching {
        Json.decodeFromString(NostrEvent.serializer(), content)
    }.getOrNull()
}

/** 行の表示に必要な参照先（返信の親・引用・アクティビティの対象）。IDとして不正なものは除く。 */
internal fun NostrEvent.journalReferencedEventIds(): Set<String> = buildSet {
    replyTargetId()?.let(::add)
    addAll(quotedEventIds(this@journalReferencedEventIds))
    activityTargetId()?.let(::add)
}.filterTo(mutableSetOf(), ::isFullEventId)
