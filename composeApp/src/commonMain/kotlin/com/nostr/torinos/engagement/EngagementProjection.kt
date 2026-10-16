package com.nostr.torinos.engagement

import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.NostrEvent

internal data class EngagementViewScope(
    val context: NoteContext = NoteContext.Timeline,
    val relayUrls: Set<String>? = null,
    val admittedEventIds: Set<String>? = null,
)

internal data class EngagementPresentation(
    val state: NoteEngagementState,
    val replyCount: Int,
    val events: TargetEngagement,
)

internal fun projectEngagement(
    engagement: TargetEngagement,
    ownPubkey: String?,
    scope: EngagementViewScope = EngagementViewScope(),
    pending: Map<EngagementSlot, PendingEngagementOperation> = emptyMap(),
): EngagementPresentation {
    fun Map<String, NostrEvent>.admitted() = filterKeys { scope.admittedEventIds == null || it in scope.admittedEventIds }
    val scoped = engagement.scopedTo(scope.relayUrls).let {
        it.copy(reactions = it.reactions.admitted(), reposts = it.reposts.admitted(),
            replies = it.replies.admitted().filterValues(scope.context::matches),
            quotes = it.quotes.admitted().filterValues(scope.context::matches))
    }
    val applicable = pending.filterValues { operation ->
        val targets = operation.deliveryRelayUrls
        scope.relayUrls == null || targets == null || targets.any { it in scope.relayUrls }
    }
    val summary = summarizeEngagement(scoped, ownPubkey)
    return EngagementPresentation(EngagementReducer.rebase(summary.toNoteEngagementState(), applicable), summary.replyCount, scoped)
}
