package com.nostr.torinos.engagement

import com.nostr.torinos.model.*

internal enum class EngagementBucket { Reactions, Reposts, Replies, Quotes, Zaps }
internal data class EngagementReference(val targetId: String, val bucket: EngagementBucket)

internal fun classifyEngagementEvent(event: NostrEvent): List<EngagementReference> {
    fun lastTarget(bucket: EngagementBucket) = listOfNotNull(
        event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)?.let { EngagementReference(it, bucket) },
    )
    return when (event.kind) {
        7 -> lastTarget(EngagementBucket.Reactions)
        6, 16 -> lastTarget(EngagementBucket.Reposts)
        // Zap レシートの検証と集計は zap パッケージで行う。ここでは投稿ごとに集めるだけ。
        com.nostr.torinos.zap.ZAP_RECEIPT_KIND -> lastTarget(EngagementBucket.Zaps)
        1, 42, COMMENT_EVENT_KIND -> {
            val context = when (event.kind) {
                42 -> event.channelRootId()?.let(NoteContext::Channel) ?: return emptyList()
                COMMENT_EVENT_KIND -> if (event.isSupportedTimelineComment()) NoteContext.Timeline else return emptyList()
                else -> NoteContext.Timeline
            }
            listOfNotNull(context.replyTargetId(event)?.let { EngagementReference(it, EngagementBucket.Replies) }) +
                (if (event.kind == COMMENT_EVENT_KIND) emptyList() else event.tags
                    .filter { it.firstOrNull() == "q" }.mapNotNull { it.getOrNull(1) }.distinct()
                    .map { EngagementReference(it, EngagementBucket.Quotes) })
        }
        else -> emptyList()
    }
}
