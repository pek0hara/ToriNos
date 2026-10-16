package com.nostr.torinos.ui.channel

import com.nostr.torinos.engagement.*
import com.nostr.torinos.model.NoteContext

/** チャンネル固有の取得先を保ち、集計だけを共有ストアから投影する。 */
internal class ChannelEngagementBinding(
    private val channelId: String,
    private val store: EngagementStore,
    private val pending: PendingEngagementOperations,
) {
    private var targets = emptySet<String>()
    private var closed = false

    fun project(ids: Set<String>, ownPubkey: String?, relays: Set<String>): Map<String, EngagementPresentation>? {
        if (closed) return null
        if (targets != ids) { targets = ids.toSet(); store.retain(this, targets) }
        val baseRevision = store.revision()
        val pendingRevision = pending.revision()
        val result = targets.associateWith { id ->
            projectEngagement(store.snapshot(id) ?: TargetEngagement(), ownPubkey,
                EngagementViewScope(NoteContext.Channel(channelId), relays), pending.operations(id))
        }
        return result.takeIf { baseRevision == store.revision() && pendingRevision == pending.revision() }
    }

    fun close() { closed = true; targets = emptySet(); store.release(this) }
}
