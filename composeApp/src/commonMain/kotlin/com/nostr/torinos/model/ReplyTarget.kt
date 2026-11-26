package com.nostr.torinos.model

data class ReplyEventReference(
    val id: String,
    val kind: Int,
    val pubkey: String,
    val relayUrl: String? = null,
    val pubkeyRelayUrl: String? = null,
    /** 記事などaddressable eventのaddress。指定時はNIP-22の`A`/`a`タグで参照する。 */
    val address: String? = null,
)

sealed interface ReplyTarget {
    val parent: ReplyEventReference
    val eventKind: Int
    fun tags(): List<List<String>>

    data class Timeline(
        val root: ReplyEventReference,
        override val parent: ReplyEventReference,
    ) : ReplyTarget {
        override val eventKind: Int = COMMENT_EVENT_KIND

        override fun tags(): List<List<String>> = buildList {
            root.address?.let { add(addressTag("A", it, root)) }
            if (root.id.isNotBlank()) add(eventTag("E", root))
            else require(root.address != null) { "返信のルートにはIDまたはaddressが必要です" }
            add(listOf("K", root.kind.toString()))
            add(pubkeyTag("P", root))
            parent.address?.let { add(addressTag("a", it, parent)) }
            add(eventTag("e", parent))
            add(listOf("k", parent.kind.toString()))
            add(pubkeyTag("p", parent))
        }
    }

    data class Channel(
        val channelId: String,
        override val parent: ReplyEventReference,
        /** チャンネル(kind 40)の relay hint。通常は relay context の primaryHint(第16.8節)。 */
        val channelRelayUrl: String? = null,
    ) : ReplyTarget {
        override val eventKind: Int = 42

        override fun tags(): List<List<String>> =
            ChannelEventTags.replyMessage(channelId, channelRelayUrl, parent)
    }
}

/**
 * ToriNos が通常タイムラインの返信として扱う、kind 1 配下の NIP-22 コメントかを検証する。
 * 作者の手がかり（E/e の4番目、P/p）は省くクライアントがあるので必須にしない。あって食い違うときだけ断る。
 * 返信するときに足りない root の作者は [com.nostr.torinos.network.resolveReplyTarget] が root から補う。
 */
fun NostrEvent.isSupportedTimelineComment(): Boolean {
    if (kind != COMMENT_EVENT_KIND) return false
    val rootEvent = tags.firstOrNull { it.firstOrNull() == "E" } ?: return false
    val rootKind = tags.firstOrNull { it.firstOrNull() == "K" }?.getOrNull(1)?.toIntOrNull()
    val parentEvent = tags.lastOrNull { it.firstOrNull() == "e" } ?: return false
    val parentKind = tags.firstOrNull { it.firstOrNull() == "k" }?.getOrNull(1)?.toIntOrNull()
    return rootKind == 1 &&
        (parentKind == 1 || parentKind == COMMENT_EVENT_KIND) &&
        rootEvent.getOrNull(1)?.isNotBlank() == true &&
        parentEvent.getOrNull(1)?.isNotBlank() == true &&
        authorHintAgrees(rootEvent, "P") &&
        authorHintAgrees(parentEvent, "p")
}

/** [eventTag] の4番目の pubkey と、[pubkeyTagName] のタグが両方あるなら一致する。 */
private fun NostrEvent.authorHintAgrees(eventTag: List<String>, pubkeyTagName: String): Boolean {
    val hinted = eventTag.getOrNull(3)?.takeIf { it.isNotBlank() } ?: return true
    val tagged = tags.filter { it.firstOrNull() == pubkeyTagName }.mapNotNull { it.getOrNull(1)?.takeIf(String::isNotBlank) }
    return tagged.isEmpty() || hinted in tagged
}

fun NostrEvent.toReplyTarget(noteContext: NoteContext): ReplyTarget? = when (noteContext) {
    NoteContext.Timeline -> toTimelineReplyTarget()
    is NoteContext.Channel -> takeIf { kind == noteContext.eventKind }?.let {
        ReplyTarget.Channel(
            channelId = noteContext.channelId,
            parent = ReplyEventReference(id = id, kind = kind, pubkey = pubkey),
        )
    }
}

fun NostrEvent.toTimelineReplyTarget(): ReplyTarget.Timeline? = when (kind) {
    1 -> {
        val parent = ReplyEventReference(id = id, kind = kind, pubkey = pubkey)
        if (tags.none { it.firstOrNull() == "e" }) {
            return ReplyTarget.Timeline(root = parent, parent = parent)
        }
        val rootHint = timelineRootHint() ?: return null
        val rootPubkey = rootHint.pubkey ?: return null
        ReplyTarget.Timeline(
            root = ReplyEventReference(
                id = rootHint.id,
                kind = 1,
                pubkey = rootPubkey,
                relayUrl = rootHint.relayUrl,
            ),
            parent = parent,
        )
    }
    COMMENT_EVENT_KIND -> {
        val rootEventTag = tags.firstOrNull { it.firstOrNull() == "E" } ?: return null
        val rootId = rootEventTag.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val rootEventPubkey = rootEventTag.getOrNull(3)?.takeIf { it.isNotBlank() }
        val rootKind = tags.firstOrNull { it.firstOrNull() == "K" }
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 0..65535 }
            ?: return null
        // E の4番目を省くクライアントがあるので、P だけでも root の作者とする。両方あって食い違うなら使わない。
        val rootPubkey = tags.firstOrNull { it.firstOrNull() == "P" }
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        if (rootEventPubkey != null && rootPubkey != rootEventPubkey) return null
        ReplyTarget.Timeline(
            root = ReplyEventReference(
                id = rootId,
                kind = rootKind,
                pubkey = rootPubkey,
                relayUrl = rootEventTag.getOrNull(2)?.takeIf { it.isNotBlank() },
                pubkeyRelayUrl = tags.firstOrNull { it.firstOrNull() == "P" }
                    ?.getOrNull(2)
                    ?.takeIf { it.isNotBlank() },
            ),
            parent = ReplyEventReference(id = id, kind = kind, pubkey = pubkey),
        )
    }
    else -> null
}

internal data class ReplyRootHint(
    val id: String,
    val relayUrl: String?,
    val pubkey: String?,
)

internal fun NostrEvent.timelineRootHint(): ReplyRootHint? = when (kind) {
    COMMENT_EVENT_KIND -> tags.firstOrNull { it.firstOrNull() == "E" }?.let { tag ->
        ReplyRootHint(
            id = tag.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null,
            relayUrl = tag.getOrNull(2)?.takeIf { it.isNotBlank() },
            pubkey = tag.getOrNull(3)?.takeIf { it.isNotBlank() },
        )
    }
    1 -> {
        val eventTags = tags.filter { it.firstOrNull() == "e" }
        if (eventTags.isEmpty()) return null
        val hasMarkers = eventTags.any { it.getOrNull(3) == "root" || it.getOrNull(3) == "reply" }
        val rootTag = eventTags.firstOrNull { it.getOrNull(3) == "root" }
            ?: eventTags.firstOrNull().takeUnless { hasMarkers }
            ?: return null
        ReplyRootHint(
            id = rootTag.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null,
            relayUrl = rootTag.getOrNull(2)?.takeIf { it.isNotBlank() },
            pubkey = rootTag.getOrNull(4)?.takeIf { it.isNotBlank() },
        )
    }
    else -> null
}

private fun addressTag(name: String, address: String, reference: ReplyEventReference): List<String> =
    listOf(name, address, reference.relayUrl.orEmpty())

private fun eventTag(name: String, reference: ReplyEventReference): List<String> =
    listOf(name, reference.id, reference.relayUrl.orEmpty(), reference.pubkey)

private fun pubkeyTag(name: String, reference: ReplyEventReference): List<String> =
    buildList {
        add(name)
        add(reference.pubkey)
        reference.pubkeyRelayUrl?.let { add(it) }
    }
