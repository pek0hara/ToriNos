package com.nostr.torinos.model

sealed interface NoteContext {
    val eventKind: Int
    val readableEventKinds: List<Int> get() = listOf(eventKind)

    fun matches(event: NostrEvent): Boolean

    fun replyTargetId(event: NostrEvent): String?

    fun replyTags(replyToId: String?, replyToPubkey: String?): List<List<String>>

    data object Timeline : NoteContext {
        override val eventKind: Int = 1
        override val readableEventKinds: List<Int> = listOf(eventKind, COMMENT_EVENT_KIND)

        override fun matches(event: NostrEvent): Boolean =
            event.kind == eventKind || event.isSupportedTimelineComment()

        override fun replyTargetId(event: NostrEvent): String? =
            event.replyTargetId()

        override fun replyTags(replyToId: String?, replyToPubkey: String?): List<List<String>> =
            buildList {
                if (replyToId != null) add(listOf("e", replyToId))
                if (replyToPubkey != null) add(listOf("p", replyToPubkey))
            }
    }

    data class Channel(val channelId: String) : NoteContext {
        override val eventKind: Int = 42

        override fun matches(event: NostrEvent): Boolean =
            event.kind == eventKind && event.channelRootId() == channelId

        override fun replyTargetId(event: NostrEvent): String? =
            event.replyTargetId()?.takeIf { it != channelId }

        override fun replyTags(replyToId: String?, replyToPubkey: String?): List<List<String>> =
            buildList {
                add(listOf("e", channelId, "", "root"))
                if (replyToId != null) add(listOf("e", replyToId, "", "reply"))
                if (replyToPubkey != null) add(listOf("p", replyToPubkey))
            }
    }
}

fun noteContextForChannel(channelId: String?): NoteContext =
    channelId?.takeIf { it.isNotBlank() }?.let { NoteContext.Channel(it) } ?: NoteContext.Timeline
